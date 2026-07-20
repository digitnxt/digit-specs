package repository

import (
	"context"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"gorm.io/gorm"

	"employee/internal/models"
	"employee/pkg/errors"

	tenantdb "github.com/digitnxt/digit3/src/libraries/tenant-migration/tenantdb"
	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
)

// EmployeeRepository defines the interface for employee data access operations.
//
// Concurrency is optimistic (version compare-and-swap), not pessimistic — there
// is no SELECT … FOR UPDATE. PATCH (partial) and PUT (full-row) updates have
// deliberately separate paths:
//   - Patch uses GORM Updates(struct) which writes only non-nil fields from
//     the EmployeePatch change-set. Suitable for partial updates.
//   - FetchForWrite + Update is the PUT pair. FetchForWrite loads the current
//     row (no lock, no jurisdictions) so the service can read-modify-write and
//     capture the expected version; Update rewrites every mutable column and
//     compare-and-swaps on version.
//
// Both Patch and Update take expectedVersion — the version the client last read.
// A zero rows-affected on a row known to exist means the version moved under the
// client → ROW_VERSION_MISMATCH (409).
type EmployeeRepository interface {
	Create(ctx context.Context, employee *models.Employee) error
	FindByUUID(ctx context.Context, uuid, tenantID string) (*models.Employee, error)
	// Patch writes only non-nil fields from the change-set, compare-and-swapping
	// on expectedVersion. Used by PATCH.
	Patch(ctx context.Context, id, tenantID string, patch *models.EmployeePatch, expectedVersion int) error
	// FetchForWrite loads the current employee row (no lock, no jurisdictions)
	// prior to a versioned write. Used by PUT/PATCH/deactivate/reactivate to
	// capture the current version for the compare-and-swap.
	FetchForWrite(ctx context.Context, uuid, tenantID string) (*models.Employee, error)
	// Update rewrites every mutable column on the supplied entity, compare-and-
	// swapping on expectedVersion. Immutable columns are Omit-ed at the SQL
	// layer. Used by PUT (and deactivate/reactivate). The entity's Version must
	// already be bumped to expectedVersion+1 by the caller.
	Update(ctx context.Context, employee *models.Employee, expectedVersion int) error
	Delete(ctx context.Context, id, tenantID string) error
	Search(ctx context.Context, tenantID string, criteria *models.EmployeeSearchCriteria) ([]*models.Employee, error)
}

type employeeRepository struct {
	db *gorm.DB
}

func NewEmployeeRepository(db *gorm.DB) EmployeeRepository {
	return &employeeRepository{db: db}
}

func (r *employeeRepository) Create(ctx context.Context, employee *models.Employee) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.create")
	defer span.End()

	now := time.Now().UnixMilli()
	employee.CreatedTime = now
	employee.ModifiedTime = now

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Create(employee)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "INSERT", "employees", duration, tx.Error == nil)
	span.SetAttributes(attribute.Int64("db.duration_ms", duration.Milliseconds()))

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to create employee")
		// Translate known unique-violations to typed sentinels so the service
		// layer can branch via errors.Is without touching pg internals.
		if translated := translatePgError(tx.Error); translated != tx.Error {
			return translated
		}
		return errors.New(errors.CodeDatabase, "failed to create employee")
	}

	span.SetStatus(codes.Ok, "Employee created")
	return nil
}

func (r *employeeRepository) FindByUUID(ctx context.Context, uuid, tenantID string) (*models.Employee, error) {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.find_by_uuid")
	defer span.End()

	span.SetAttributes(attribute.String("employee.uuid", uuid))

	start := time.Now()
	var employee models.Employee
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Where("id = ? AND tenant_id = ?", uuid, tenantID).First(&employee)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "SELECT", "employees", duration, tx.Error == nil || tx.Error == gorm.ErrRecordNotFound)
	span.SetAttributes(attribute.Int64("db.duration_ms", duration.Milliseconds()))

	if tx.Error != nil {
		if tx.Error == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "Employee not found")
			return nil, errors.ErrNotFound.WithDescription("employee not found")
		}
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to find employee by UUID")
		return nil, errors.New(errors.CodeDatabase, "failed to find employee by ID")
	}

	// Batch-fetch jurisdictions for this employee in the same transaction.
	// Mirrors billing's GetByIDs pattern (explicit IN query + tenant filter)
	// rather than GORM Preload — no service in the monorepo uses Preload,
	// so we keep that consistency.
	jurs, err := r.loadJurisdictions(ctx, db, []string{employee.ID}, tenantID)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "Failed to load jurisdictions")
		return nil, errors.New(errors.CodeDatabase, "failed to load jurisdictions")
	}
	employee.Jurisdictions = jurs[employee.ID]

	span.SetStatus(codes.Ok, "Employee found")
	return &employee, nil
}

// loadJurisdictions fetches all jurisdictions belonging to the given employee
// IDs in a single SELECT (IN clause). Returns a map keyed by employee_id for
// O(1) attachment by callers. Tenant filter is applied at the app layer for
// parity with the rest of the repo — schema-level isolation (search_path)
// only fires when SCHEMA_SEPARATION_MODE is true; we must filter explicitly
// to stay correct in the lenient mode.
func (r *employeeRepository) loadJurisdictions(ctx context.Context, db *gorm.DB, employeeIDs []string, tenantID string) (map[string][]*models.Jurisdiction, error) {
	if len(employeeIDs) == 0 {
		return nil, nil
	}
	var jurs []*models.Jurisdiction
	start := time.Now()
	err := db.WithContext(ctx).
		Where("employee_id IN ? AND tenant_id = ?", employeeIDs, tenantID).
		Order(`"createdTime" DESC`).
		Find(&jurs).Error
	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "jurisdictions", duration, err == nil)
	if err != nil {
		return nil, err
	}
	out := make(map[string][]*models.Jurisdiction, len(employeeIDs))
	for _, j := range jurs {
		out[j.EmployeeID] = append(out[j.EmployeeID], j)
	}
	return out, nil
}

// Patch applies a partial update via GORM's Updates(struct) semantics —
// nil pointer fields on EmployeePatch are skipped, non-nil pointers (even to
// a zero value) are written. Used by the PATCH endpoint.
//
// tenant_id is filtered in the WHERE as defense-in-depth — tenantdb sets
// search_path for schema-level isolation, but application-side scoping is
// still required so a cross-tenant id can never land on the wrong row.
func (r *employeeRepository) Patch(ctx context.Context, id, tenantID string, patch *models.EmployeePatch, expectedVersion int) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.patch")
	defer span.End()

	span.SetAttributes(attribute.String("employee.id", id))

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	// Compare-and-swap: the version predicate makes the write fail (0 rows) if the
	// row moved since the service read it. patch.Version already carries the bump
	// (expected+1) and is written unconditionally (plain int on the change-set).
	tx := db.WithContext(ctx).Model(&models.Employee{}).
		Where("id = ? AND tenant_id = ? AND version = ?", id, tenantID, expectedVersion).
		Updates(patch)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "UPDATE", "employees", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int64("db.rows_affected", tx.RowsAffected),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to patch employee")
		return errors.New(errors.CodeDatabase, "failed to patch employee")
	}
	// Existence is verified by the service's FetchForWrite before this call, so a
	// zero rows-affected here means the version moved under the client → 409.
	if tx.RowsAffected == 0 {
		span.SetStatus(codes.Ok, "Employee version mismatch")
		return errors.New(errors.CodeRowVersionMismatch, "employee was modified concurrently")
	}

	span.SetStatus(codes.Ok, "Employee patched")
	return nil
}

// FetchForWrite loads the current employee row (plain SELECT — no lock, no
// jurisdictions) so the service can read-modify-write and capture the expected
// version for the compare-and-swap. Concurrency is optimistic: the authoritative
// guard is the version predicate on Update/Patch, not a row lock.
func (r *employeeRepository) FetchForWrite(ctx context.Context, uuid, tenantID string) (*models.Employee, error) {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.fetch_for_write")
	defer span.End()

	span.SetAttributes(attribute.String("employee.uuid", uuid))

	start := time.Now()
	var employee models.Employee
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).
		Where("id = ? AND tenant_id = ?", uuid, tenantID).
		First(&employee)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "SELECT", "employees", duration, tx.Error == nil || tx.Error == gorm.ErrRecordNotFound)
	span.SetAttributes(attribute.Int64("db.duration_ms", duration.Milliseconds()))

	if tx.Error != nil {
		if tx.Error == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "Employee not found")
			return nil, errors.ErrNotFound.WithDescription("employee not found")
		}
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to fetch employee for write")
		return nil, errors.New(errors.CodeDatabase, "failed to fetch employee for write")
	}

	span.SetStatus(codes.Ok, "Employee loaded for write")
	return &employee, nil
}

// Update writes the full mutable surface of the supplied entity, compare-and-
// swapping on expectedVersion. Used by the PUT endpoint (and deactivate/
// reactivate). Select("*") overrides GORM's default zero-value-skip rule so
// e.g. IsActive=false actually writes false; Omit() guards immutable columns
// from accidental rewrite even if the entity carries different values for them.
//
// The caller must have bumped employee.Version to expectedVersion+1 before this
// call. The WHERE pins both id and version: the entity was just loaded by
// FetchForWrite(uuid, tenantID) so identity is trustworthy, and the version
// predicate closes the read→write race — 0 rows means a concurrent mutation.
func (r *employeeRepository) Update(ctx context.Context, employee *models.Employee, expectedVersion int) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.update")
	defer span.End()

	span.SetAttributes(attribute.String("employee.id", employee.ID))

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Model(&models.Employee{}).
		Where("id = ? AND version = ?", employee.ID, expectedVersion).
		Select("*").
		Omit("id", "code", "user_id", "individual_id", "date_of_appointment", "tenant_id", "createdBy", "createdTime").
		Updates(employee)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "UPDATE", "employees", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int64("db.rows_affected", tx.RowsAffected),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to update employee")
		return errors.New(errors.CodeDatabase, "failed to update employee")
	}
	// Existence is verified by the service's FetchForWrite before this call, so a
	// zero rows-affected here means the version moved under the client → 409.
	if tx.RowsAffected == 0 {
		span.SetStatus(codes.Ok, "Employee version mismatch")
		return errors.New(errors.CodeRowVersionMismatch, "employee was modified concurrently")
	}

	span.SetStatus(codes.Ok, "Employee updated")
	return nil
}

func (r *employeeRepository) Delete(ctx context.Context, id, tenantID string) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.delete")
	defer span.End()

	span.SetAttributes(attribute.String("employee.id", id))

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).Delete(&models.Employee{})
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "DELETE", "employees", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int64("db.rows_affected", tx.RowsAffected),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to delete employee")
		return errors.New(errors.CodeDatabase, "failed to delete employee")
	}
	if tx.RowsAffected == 0 {
		span.SetStatus(codes.Ok, "Employee not found")
		return errors.ErrNotFound.WithDescription("employee not found")
	}

	span.SetStatus(codes.Ok, "Employee deleted")
	return nil
}

func (r *employeeRepository) Search(ctx context.Context, tenantID string, criteria *models.EmployeeSearchCriteria) ([]*models.Employee, error) {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.employee.search")
	defer span.End()

	span.SetAttributes(attribute.String("tenant.id", tenantID))

	var employees []*models.Employee

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Model(&models.Employee{}).Where("tenant_id = ?", tenantID)

	if len(criteria.IDs) > 0 {
		tx = tx.Where("id IN ?", criteria.IDs)
	}
	if len(criteria.Codes) > 0 {
		tx = tx.Where("code IN ?", criteria.Codes)
	}
	// UserIDs is populated by the service from Keycloak role resolution (see
	// SearchEmployees). The service short-circuits an empty set, so reaching
	// here with len > 0 means a real role→member list to filter against.
	if len(criteria.UserIDs) > 0 {
		tx = tx.Where("user_id IN ?", criteria.UserIDs)
	}
	if len(criteria.Statuses) > 0 {
		tx = tx.Where("status IN ?", criteria.Statuses)
	}
	if len(criteria.EmployeeTypes) > 0 {
		tx = tx.Where("employee_type IN ?", criteria.EmployeeTypes)
	}
	if len(criteria.Departments) > 0 {
		tx = tx.Where("department IN ?", criteria.Departments)
	}
	if len(criteria.Designations) > 0 {
		tx = tx.Where("designation IN ?", criteria.Designations)
	}
	if criteria.DateOfAppointmentFrom != nil {
		tx = tx.Where("date_of_appointment >= ?", *criteria.DateOfAppointmentFrom)
	}
	if criteria.DateOfAppointmentTo != nil {
		tx = tx.Where("date_of_appointment <= ?", *criteria.DateOfAppointmentTo)
	}
	if criteria.IsActive != nil {
		tx = tx.Where("is_active = ?", *criteria.IsActive)
	}

	tx = tx.Order("\"createdTime\" DESC")

	if criteria.Limit > 0 {
		tx = tx.Limit(criteria.Limit)
	}
	if criteria.Offset > 0 {
		tx = tx.Offset(criteria.Offset)
	}

	tx = tx.Find(&employees)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "SELECT", "employees", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int("employee.found_count", len(employees)),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to search employees")
		return nil, errors.New(errors.CodeDatabase, "failed to search employees")
	}

	// Batch-attach jurisdictions in one IN-query so the search response
	// stays at 2 round-trips total regardless of result count (vs N+1 when
	// the service did per-employee SearchJurisdictions calls).
	if len(employees) > 0 {
		ids := make([]string, 0, len(employees))
		for _, e := range employees {
			ids = append(ids, e.ID)
		}
		jurs, err := r.loadJurisdictions(ctx, db, ids, tenantID)
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "Failed to load jurisdictions for search")
			return nil, errors.New(errors.CodeDatabase, "failed to load jurisdictions")
		}
		for _, e := range employees {
			e.Jurisdictions = jurs[e.ID]
		}
	}

	span.SetStatus(codes.Ok, "Employees searched")
	return employees, nil
}
