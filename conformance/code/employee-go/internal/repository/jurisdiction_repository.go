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

type JurisdictionRepository interface {
	Create(ctx context.Context, jurisdiction *models.Jurisdiction) error
	FindByUUID(ctx context.Context, uuid, tenantID string) (*models.Jurisdiction, error)
	// Update rewrites the mutable columns compare-and-swapping on expectedVersion.
	// The entity's Version must already be bumped to expectedVersion+1.
	Update(ctx context.Context, jurisdiction *models.Jurisdiction, expectedVersion int) error
	// DeactivateOmitted soft-deletes (is_active=false, version bumped) every
	// active jurisdiction of the employee whose id is NOT in keepIDs. Used by the
	// employee PUT/PATCH reconcile to drop jurisdictions left out of the supplied
	// array. An empty keepIDs deactivates all active jurisdictions.
	DeactivateOmitted(ctx context.Context, employeeID, tenantID, userID string, keepIDs []string) error
	Delete(ctx context.Context, id, tenantID string) error
	Search(ctx context.Context, tenantID, employeeID string, criteria *models.JurisdictionSearchCriteria) ([]*models.Jurisdiction, error)
}

type jurisdictionRepository struct {
	db *gorm.DB
}

func NewJurisdictionRepository(db *gorm.DB) JurisdictionRepository {
	return &jurisdictionRepository{db: db}
}

func (r *jurisdictionRepository) Create(ctx context.Context, jurisdiction *models.Jurisdiction) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.create")
	defer span.End()

	now := time.Now().UnixMilli()
	jurisdiction.CreatedTime = now
	jurisdiction.ModifiedTime = now

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Create(jurisdiction)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "INSERT", "jurisdictions", duration, tx.Error == nil)
	span.SetAttributes(attribute.Int64("db.duration_ms", duration.Milliseconds()))

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to create jurisdiction")
		// Translate known constraint violations to typed sentinels so the
		// service can branch via errors.Is without inspecting pg internals.
		// FK violation on employee_id surfaces as ErrJurisdictionEmployeeNotFound
		// → 404, replacing the generic 500 DATABASE_ERROR for that case.
		if translated := translatePgError(tx.Error); translated != tx.Error {
			return translated
		}
		return errors.New(errors.CodeDatabase, "failed to create jurisdiction")
	}

	span.SetStatus(codes.Ok, "Jurisdiction created")
	return nil
}

func (r *jurisdictionRepository) FindByUUID(ctx context.Context, uuid, tenantID string) (*models.Jurisdiction, error) {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.find_by_uuid")
	defer span.End()

	span.SetAttributes(attribute.String("jurisdiction.uuid", uuid))

	start := time.Now()
	var jurisdiction models.Jurisdiction
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Model(&models.Jurisdiction{}).
		Where("id = ? AND tenant_id = ?", uuid, tenantID).
		First(&jurisdiction)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "SELECT", "jurisdictions", duration, tx.Error == nil || tx.Error == gorm.ErrRecordNotFound)
	span.SetAttributes(attribute.Int64("db.duration_ms", duration.Milliseconds()))

	if tx.Error != nil {
		if tx.Error == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "Jurisdiction not found")
			return nil, errors.ErrNotFound.WithDescription("jurisdiction not found")
		}
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to find jurisdiction by UUID")
		return nil, errors.New(errors.CodeDatabase, "failed to find jurisdiction")
	}

	span.SetStatus(codes.Ok, "Jurisdiction found")
	return &jurisdiction, nil
}

func (r *jurisdictionRepository) Update(ctx context.Context, jurisdiction *models.Jurisdiction, expectedVersion int) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.update")
	defer span.End()

	span.SetAttributes(
		attribute.String("jurisdiction.id", jurisdiction.ID),
		attribute.String("employee.id", jurisdiction.EmployeeID),
	)

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	// PUT semantics — overwrite every mutable column from the fully-populated
	// entity built by UpdateJurisdictionRequest.ToEntity. Select("*") overrides
	// GORM's default zero-value-skip rule so e.g. IsActive=false is actually
	// written. Omit() guards immutable columns from accidental rewrite even
	// though ToEntity carries them forward from the loaded row.
	//
	// The WHERE pins id and version: the entity was just loaded by FindByUUID
	// and verified for employee ownership by the service, and the version
	// predicate compare-and-swaps — 0 rows means a concurrent mutation. The
	// caller must have bumped jurisdiction.Version to expectedVersion+1.
	tx := db.WithContext(ctx).Model(&models.Jurisdiction{}).
		Where("id = ? AND version = ?", jurisdiction.ID, expectedVersion).
		Select("*").
		Omit("id", "employee_id", "tenant_id", "createdBy", "createdTime").
		Updates(jurisdiction)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "UPDATE", "jurisdictions", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int64("db.rows_affected", tx.RowsAffected),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to update jurisdiction")
		return errors.New(errors.CodeDatabase, "failed to update jurisdiction")
	}
	// Existence + ownership are verified by the service before this call, so a
	// zero rows-affected here means the version moved under the client → 409.
	if tx.RowsAffected == 0 {
		span.SetStatus(codes.Ok, "Jurisdiction version mismatch")
		return errors.New(errors.CodeRowVersionMismatch, "jurisdiction was modified concurrently")
	}

	span.SetStatus(codes.Ok, "Jurisdiction updated")
	return nil
}

// DeactivateOmitted soft-deletes every active jurisdiction of the employee whose
// id is not in keepIDs, in a single UPDATE (is_active=false, version bumped,
// audit set). Used by the employee reconcile to drop jurisdictions the client
// left out of the supplied array. An empty keepIDs deactivates all active rows.
func (r *jurisdictionRepository) DeactivateOmitted(ctx context.Context, employeeID, tenantID, userID string, keepIDs []string) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.deactivate_omitted")
	defer span.End()

	span.SetAttributes(
		attribute.String("employee.id", employeeID),
		attribute.Int("jurisdiction.keep_count", len(keepIDs)),
	)

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Model(&models.Jurisdiction{}).
		Where("employee_id = ? AND tenant_id = ? AND is_active = ?", employeeID, tenantID, true)
	// Guard the empty-slice case: `id NOT IN ()` would render `NOT IN (NULL)`
	// (always NULL → no rows) — the opposite of "deactivate everything". Only add
	// the exclusion when there is something to keep.
	if len(keepIDs) > 0 {
		tx = tx.Where("id NOT IN ?", keepIDs)
	}
	tx = tx.Updates(map[string]interface{}{
		"is_active":    false,
		"version":      gorm.Expr("version + 1"),
		"modifiedBy":   userID,
		"modifiedTime": time.Now().UnixMilli(),
	})
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "UPDATE", "jurisdictions", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int64("db.rows_affected", tx.RowsAffected),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to deactivate omitted jurisdictions")
		return errors.New(errors.CodeDatabase, "failed to deactivate omitted jurisdictions")
	}

	span.SetStatus(codes.Ok, "Omitted jurisdictions deactivated")
	return nil
}

func (r *jurisdictionRepository) Delete(ctx context.Context, id, tenantID string) error {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.delete")
	defer span.End()

	span.SetAttributes(attribute.String("jurisdiction.id", id))

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).
		Where("id = ? AND tenant_id = ?", id, tenantID).
		Delete(&models.Jurisdiction{})
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "DELETE", "jurisdictions", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int64("db.rows_affected", tx.RowsAffected),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to delete jurisdiction")
		return errors.New(errors.CodeDatabase, "failed to delete jurisdiction")
	}
	if tx.RowsAffected == 0 {
		span.SetStatus(codes.Ok, "Jurisdiction not found")
		return errors.ErrNotFound.WithDescription("jurisdiction not found")
	}

	span.SetStatus(codes.Ok, "Jurisdiction deleted")
	return nil
}

func (r *jurisdictionRepository) Search(ctx context.Context, tenantID, employeeID string, criteria *models.JurisdictionSearchCriteria) ([]*models.Jurisdiction, error) {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.search")
	defer span.End()

	span.SetAttributes(
		attribute.String("tenant.id", tenantID),
		attribute.String("employee.id", employeeID),
	)

	var jurisdictions []*models.Jurisdiction

	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Model(&models.Jurisdiction{}).Where("tenant_id = ?", tenantID)

	// employeeID is the path-scoped owner; an empty string means "no owner
	// filter" (only used by internal callers that genuinely don't have one).
	if employeeID != "" {
		tx = tx.Where("employee_id = ?", employeeID)
	}
	if len(criteria.IDs) > 0 {
		tx = tx.Where("id IN ?", criteria.IDs)
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

	tx = tx.Find(&jurisdictions)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "SELECT", "jurisdictions", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int("jurisdiction.found_count", len(jurisdictions)),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to search jurisdictions")
		return nil, errors.New(errors.CodeDatabase, "failed to search jurisdictions")
	}

	span.SetStatus(codes.Ok, "Jurisdictions searched")
	return jurisdictions, nil
}

func (r *jurisdictionRepository) FindByEmployeeID(ctx context.Context, employeeID, tenantID string) ([]*models.Jurisdiction, error) {
	tracer := otel.Tracer("employee-repository")
	ctx, span := tracer.Start(ctx, "db.jurisdiction.find_by_employee_id")
	defer span.End()

	span.SetAttributes(attribute.String("employee.id", employeeID))

	var jurisdictions []*models.Jurisdiction
	start := time.Now()
	db := tenantdb.GetTenantDB(ctx, r.db)
	tx := db.WithContext(ctx).Model(&models.Jurisdiction{}).
		Where("employee_id = ? AND tenant_id = ?", employeeID, tenantID).
		Find(&jurisdictions)
	duration := time.Since(start)

	tracerobs.RecordDBOperation(ctx, "SELECT", "jurisdictions", duration, tx.Error == nil)
	span.SetAttributes(
		attribute.Int64("db.duration_ms", duration.Milliseconds()),
		attribute.Int("jurisdiction.found_count", len(jurisdictions)),
	)

	if tx.Error != nil {
		span.RecordError(tx.Error)
		span.SetStatus(codes.Error, "Failed to find jurisdictions by employee ID")
		return nil, errors.New(errors.CodeDatabase, "failed to find jurisdictions by employee ID")
	}

	span.SetStatus(codes.Ok, "Jurisdictions found")
	return jurisdictions, nil
}
