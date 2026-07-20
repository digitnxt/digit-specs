package service

import (
	"context"
	stderrors "errors"
	"fmt"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"

	"employee/internal/clients/idgen"
	"employee/internal/clients/individual"
	"employee/internal/clients/keycloak"
	"employee/internal/config"
	"employee/internal/constants"
	"employee/internal/middleware"
	"employee/internal/models"
	"employee/internal/pubsub"
	"employee/internal/repository"
	"employee/pkg/errors"
	"employee/pkg/observability"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
)

type employeeService struct {
	repo             repository.EmployeeRepository
	jurisdictionSvc  JurisdictionService
	idGenClient      idgen.Client
	individualClient *individual.Client
	keycloakClient   *keycloak.Client
	config           *config.Config
	eventPublisher   *pubsub.EventPublisher
	logger           *tracerobs.OTelLogger
}

func NewEmployeeService(repo repository.EmployeeRepository, jurisdictionSvc JurisdictionService, idGenClient idgen.Client, individualClient *individual.Client, keycloakClient *keycloak.Client, config *config.Config, eventPublisher *pubsub.EventPublisher) EmployeeService {
	return &employeeService{
		repo:             repo,
		jurisdictionSvc:  jurisdictionSvc,
		idGenClient:      idGenClient,
		individualClient: individualClient,
		keycloakClient:   keycloakClient,
		config:           config,
		eventPublisher:   eventPublisher,
		logger:           tracerobs.GetOTelLogger(),
	}
}

// maxCreateBatch bounds how many employees one POST may create. The whole
// batch runs in a single request transaction and each record costs a Keycloak
// + Individual round-trip, so an unbounded batch is a resource risk.
const maxCreateBatch = constants.MaxCreateBatch

// minDateOfAppointment is the earliest date we accept for dateOfAppointment.
// Anything before this is treated as garbage input rather than a real date.
var minDateOfAppointment = time.Date(constants.MinAppointmentYear, 1, 1, 0, 0, 0, 0, time.UTC)

// validateDateOfAppointment enforces the appointment-date range in-process.
// The field is optional (nil → no-op). A supplied value must not be in the
// future (you can't be appointed after "now") and must not predate 1900.
// Returns a typed VALIDATION_ERROR (400) on violation.
func validateDateOfAppointment(d *time.Time) error {
	if d == nil {
		return nil
	}
	if d.After(time.Now()) {
		return errors.New(errors.CodeValidation, "dateOfAppointment cannot be in the future")
	}
	if d.Before(minDateOfAppointment) {
		return errors.New(errors.CodeValidation, "dateOfAppointment is too far in the past")
	}
	return nil
}

func (s *employeeService) generateEmployeeCode(ctx context.Context, tenantID string) (string, error) {
	// idgen is a downstream dependency, like Keycloak/Individual/Boundary — a
	// failure or empty answer is not the client's fault, so classify it as
	// DOWNSTREAM_ERROR (502, retryable) rather than ID_GENERATION_ERROR (500),
	// keeping dependency-failure semantics consistent across the service.
	ids, err := s.idGenClient.GenerateIDs(ctx, tenantID, 1, nil)
	if err != nil {
		return "", errors.New(errors.CodeDownstream, "failed to generate employee code")
	}
	if len(ids) == 0 {
		return "", errors.New(errors.CodeDownstream, "idgen returned no ID")
	}
	return ids[0], nil
}

// validateIndividualID validates the supplied individualId against the
// Individual service. The field is always optional at the API contract —
// callers may omit it (empty string) and the function early-returns.
//
// Two failure modes are distinguished:
//   - Individual service unreachable / returned an error → DOWNSTREAM_ERROR
//     (maps to 502 — our dependency failed, client should retry).
//   - Service answered and the id is unknown → INVALID_REQUEST (maps to 400 —
//     the client referenced a non-existent entity).
func (s *employeeService) validateIndividualID(ctx context.Context, tenantID, individualID string) error {
	// Dependency flag: when the individual service is disabled we persist without validating.
	if !s.config.Individual.Enabled {
		return nil
	}
	if individualID == "" {
		return nil
	}
	individual, err := s.individualClient.GetIndividualByID(ctx, tenantID, individualID)
	if err != nil {
		return errors.New(errors.CodeDownstream, "individual service lookup failed")
	}
	if individual == nil {
		return errors.New(errors.CodeInvalidRequest, "individual not found")
	}
	return nil
}

// validateUserID validates the supplied userId against Keycloak. Same
// optional-when-empty contract as validateIndividualID — callers may omit
// the field, and only a supplied value is round-tripped through the
// external identity service.
//
// Two failure modes, mirroring validateIndividualID:
//   - Keycloak unreachable / returned an error → DOWNSTREAM_ERROR (502).
//   - Keycloak answered but the user is unknown → INVALID_REQUEST (400).
func (s *employeeService) validateUserID(ctx context.Context, tenantID, userID string, authHeader string) error {
	// Dependency flag: when keycloak is disabled we persist without validating the userId.
	if !s.config.Keycloak.Enabled {
		return nil
	}
	if userID == "" {
		return nil
	}
	user, err := s.keycloakClient.GetUserByID(ctx, tenantID, userID, authHeader)
	if err != nil {
		return errors.New(errors.CodeDownstream, "keycloak lookup failed")
	}
	if user == nil {
		return errors.New(errors.CodeInvalidRequest, "user not found in Keycloak")
	}
	return nil
}

// toEmployeeResponse maps the persistence entity to the API DTO. Jurisdictions
// are taken from emp.Jurisdictions — the repository batch-loads them as part
// of FindByUUID and Search, so this function does not issue any DB calls and
// returns no error.
func toEmployeeResponse(emp *models.Employee) *models.EmployeeResponse {
	if emp == nil {
		return nil
	}

	jurisdictions := make([]*models.JurisdictionResponse, 0, len(emp.Jurisdictions))
	for _, j := range emp.Jurisdictions {
		jurisdictions = append(jurisdictions, toJurisdictionResponse(j))
	}

	return &models.EmployeeResponse{
		ID:                emp.ID,
		Code:              emp.Code,
		UserID:            emp.UserID,
		IndividualID:      emp.IndividualID,
		Status:            emp.Status,
		EmployeeType:      emp.EmployeeType,
		DateOfAppointment: emp.DateOfAppointment,
		Department:        emp.Department,
		Designation:       emp.Designation,
		IsActive:          emp.IsActive,
		Version:           emp.Version,
		Jurisdictions:     jurisdictions,
		AuditDetail: models.AuditDetail{
			CreatedBy:    emp.CreatedBy,
			ModifiedBy:   emp.ModifiedBy,
			CreatedTime:  emp.CreatedTime,
			ModifiedTime: emp.ModifiedTime,
		},
	}
}

func (s *employeeService) CreateEmployees(ctx context.Context, req []*models.CreateEmployeeRequest, authHeader string) ([]*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.create")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("tenant.id", tenantID),
		attribute.Int("employee.count", len(req)),
	)

	// Empty batch is a client mistake — reject up-front rather than silently
	// returning an empty 201. The OpenAPI also declares minItems: 1.
	if len(req) == 0 {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"empty request body", errors.New(errors.CodeInvalidRequest, "at least one employee record is required"),
			map[string]interface{}{"tenantId": tenantID})
	}

	// Cap the batch size to bound per-request work (the whole batch runs in one
	// request transaction with a Keycloak + Individual round-trip per record).
	// Mirrors the empty-batch guard and the OpenAPI maxItems.
	if len(req) > maxCreateBatch {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"batch too large",
			errors.New(errors.CodeInvalidRequest, fmt.Sprintf("at most %d employee records may be created per request", maxCreateBatch)),
			map[string]interface{}{"tenantId": tenantID, "count": len(req)})
	}

	s.logger.InfoWithTrace(ctx, "Creating employees", map[string]interface{}{
		"tenantId": tenantID,
		"count":    len(req),
	})

	responses := make([]*models.EmployeeResponse, 0, len(req))

	for _, r := range req {
		if err := s.validateUserID(ctx, tenantID, r.UserID, authHeader); err != nil {
			return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
				"user ID validation failed", err,
				map[string]interface{}{"tenantId": tenantID})
		}

		if err := s.validateIndividualID(ctx, tenantID, r.IndividualID); err != nil {
			return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
				"individual ID validation failed", err,
				map[string]interface{}{"tenantId": tenantID})
		}

		if err := validateDateOfAppointment(r.DateOfAppointment); err != nil {
			return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
				"dateOfAppointment validation failed", err,
				map[string]interface{}{"tenantId": tenantID})
		}

		if r.Code == "" {
			code, err := s.generateEmployeeCode(ctx, tenantID)
			if err != nil {
				return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
					"failed to generate employee code", err,
					map[string]interface{}{"tenantId": tenantID})
			}
			r.Code = code
		}

		now := time.Now().UnixMilli()
		employee := &models.Employee{
			Code:              r.Code,
			UserID:            r.UserID,
			IndividualID:      r.IndividualID,
			Status:            r.Status,
			EmployeeType:      r.EmployeeType,
			DateOfAppointment: r.DateOfAppointment,
			Department:        r.Department,
			Designation:       r.Designation,
			IsActive:          true,
			Version:           1,
			TenantID:          tenantID,
			CreatedBy:         userID,
			ModifiedBy:        userID,
			CreatedTime:       now,
			ModifiedTime:      now,
		}
		if r.IsActive != nil {
			employee.IsActive = *r.IsActive
		}

		if err := s.repo.Create(ctx, employee); err != nil {
			if stderrors.Is(err, repository.ErrDuplicateEmployeeCode) {
				return nil, failOp(ctx, span, s.logger, employeeServiceName,
					errors.CodeEmployeeExists,
					"employee with code '"+employee.Code+"' already exists for this tenant", err,
					map[string]interface{}{"tenantId": tenantID, "code": employee.Code})
			}
			return nil, failOp(ctx, span, s.logger, employeeServiceName,
				errors.CodeDatabase,
				"failed to create employee", err,
				map[string]interface{}{"tenantId": tenantID})
		}

		// Jurisdictions ride the same request transaction. Returning the
		// error rolls back the employee insert too — otherwise an invalid
		// jurisdiction would leave a half-built record in the DB while the
		// client thinks the create failed (silent partial success).
		if len(r.Jurisdictions) > 0 {
			for _, j := range r.Jurisdictions {
				jurisReq := &models.CreateJurisdictionRequest{
					BoundaryRelation: j.BoundaryRelation,
					IsActive:         &j.IsActive,
				}
				if _, err := s.jurisdictionSvc.CreateJurisdiction(ctx, employee.ID, jurisReq); err != nil {
					return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
						"failed to create jurisdiction for employee", err,
						map[string]interface{}{"tenantId": tenantID, "employeeId": employee.ID})
				}
			}
		}

		temp, err := s.repo.FindByUUID(ctx, employee.ID, tenantID)
		if err != nil {
			return nil, failOp(ctx, span, s.logger, employeeServiceName,
				errors.CodeDatabase,
				"failed to fetch created employee", err,
				map[string]interface{}{"tenantId": tenantID})
		}

		responses = append(responses, toEmployeeResponse(temp))
	}

	publishMutationEvent(ctx, s.eventPublisher, s.config.PubSub.Topics.CreateEmployee, "CREATE", tenantID, responses, len(responses))
	observability.RecordEmployeeCreated(ctx, tenantID, len(responses))

	span.SetAttributes(attribute.Int("employee.created_count", len(responses)))
	span.SetStatus(codes.Ok, "Employees created")
	return responses, nil
}

func (s *employeeService) SearchEmployees(ctx context.Context, criteria *models.EmployeeSearchCriteria, authHeader string) ([]*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.search")
	defer span.End()

	tenantID := middleware.GetRequestContextFromContext(ctx).TenantID

	span.SetAttributes(attribute.String("tenant.id", tenantID))

	// Role search: employees have no role column — a role is held in Keycloak
	// against employee.user_id. Resolve the role to its Keycloak member user
	// IDs, then let the repo filter user_id IN (...). Pagination stays at the
	// DB so role composes with the other filters and limit/offset stay
	// consistent (see EmployeeSearchCriteria.Role).
	if criteria.Role != "" {
		// The role filter's only purpose is to resolve members via keycloak; if keycloak is
		// disabled the feature is unavailable — fail loudly rather than return empty/over-broad.
		if !s.config.Keycloak.Enabled {
			return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
				"role search requires keycloak enabled",
				errors.New(errors.CodeValidation, "role-based search requires keycloak to be enabled"),
				map[string]interface{}{"tenantId": tenantID})
		}
		span.SetAttributes(attribute.String("employee.role", criteria.Role))

		userIDs, err := s.keycloakClient.GetUserIDsByRole(ctx, tenantID, criteria.Role, authHeader)
		if err != nil {
			return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
				"failed to resolve role members from Keycloak",
				errors.New(errors.CodeDownstream, "keycloak role lookup failed"),
				map[string]interface{}{"tenantId": tenantID, "role": criteria.Role})
		}

		// No user holds this role → no employee can match. Short-circuit before
		// hitting the DB: an empty UserIDs would otherwise render `user_id IN ()`
		// (which the repo skips as "no filter"), returning every employee.
		if len(userIDs) == 0 {
			observability.RecordEmployeeSearched(ctx, tenantID, 0)
			span.SetAttributes(attribute.Int("employee.found_count", 0))
			span.SetStatus(codes.Ok, "No employees matched role")
			return []*models.EmployeeResponse{}, nil
		}

		criteria.UserIDs = userIDs
	}

	employees, err := s.repo.Search(ctx, tenantID, criteria)
	if err != nil {
		return nil, failOp(ctx, span, s.logger, employeeServiceName,
			errors.CodeDatabase,
			"failed to search employees", err,
			map[string]interface{}{"tenantId": tenantID})
	}

	responses := make([]*models.EmployeeResponse, 0, len(employees))
	for _, emp := range employees {
		responses = append(responses, toEmployeeResponse(emp))
	}

	observability.RecordEmployeeSearched(ctx, tenantID, len(responses))

	span.SetAttributes(attribute.Int("employee.found_count", len(responses)))
	span.SetStatus(codes.Ok, "Employees searched")
	return responses, nil
}

func (s *employeeService) GetEmployeeByUUID(ctx context.Context, uuid string) (*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.get_by_uuid")
	defer span.End()

	tenantID := middleware.GetRequestContextFromContext(ctx).TenantID

	span.SetAttributes(
		attribute.String("employee.uuid", uuid),
		attribute.String("tenant.id", tenantID),
	)

	employee, err := s.repo.FindByUUID(ctx, uuid, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, employeeServiceName, "GetEmployeeByUUID", "employee", err)
	}

	span.SetStatus(codes.Ok, "Employee retrieved")
	return toEmployeeResponse(employee), nil
}

// UpdateEmployee applies a PUT-style update to an existing employee.
//
// Flow: load the current row → optimistic version check (fast-fail) → carry
// immutables forward via ToEntity → bump version → write the full mutable
// surface back via Select(*) + Omit(immutables), compare-and-swapping on the
// version → reconcile the jurisdiction collection from the supplied array.
//
// Concurrency is optimistic: the client sends the version it last read; the
// versioned write (CAS) rejects a stale PUT with 409 ROW_VERSION_MISMATCH,
// closing the read→write race without holding a row lock across the downstream
// jurisdiction reconcile.
//
// Required-set is enforced at bind time (see UpdateEmployeeRequest); this
// method does not re-validate field presence.
func (s *employeeService) UpdateEmployee(ctx context.Context, uuid string, req *models.UpdateEmployeeRequest) (*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.update")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("employee.uuid", uuid),
		attribute.String("tenant.id", tenantID),
	)

	s.logger.InfoWithTrace(ctx, "Updating employee", map[string]interface{}{
		"uuid":     uuid,
		"tenantId": tenantID,
	})

	existing, err := s.repo.FetchForWrite(ctx, uuid, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, employeeServiceName, "UpdateEmployee", "employee", err)
	}

	// Optimistic fast-fail before touching jurisdictions. The authoritative guard
	// is the CAS in repo.Update; this returns a clean 409 up-front on staleness.
	if existing.Version != req.Version {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"employee version mismatch",
			errors.New(errors.CodeRowVersionMismatch, "employee was modified concurrently"),
			map[string]interface{}{"uuid": uuid})
	}

	expectedVersion := existing.Version
	updated := req.ToEntity(userID, *existing)
	updated.Version = expectedVersion + 1
	if err := s.repo.Update(ctx, &updated, expectedVersion); err != nil {
		// Preserve the typed code — a CAS miss is ROW_VERSION_MISMATCH (409).
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to update employee", err, map[string]interface{}{"uuid": uuid})
	}

	// Jurisdictions are required in the PUT body (strict full-state). Reconcile:
	// id+version → update in place, id-less → insert, omitted → deactivate. Empty
	// array clears the collection.
	if err := s.jurisdictionSvc.ReconcileJurisdictions(ctx, uuid, req.Jurisdictions); err != nil {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to reconcile jurisdictions", err,
			map[string]interface{}{"uuid": uuid})
	}

	resp, err := s.GetEmployeeByUUID(ctx, uuid)
	if err != nil {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to read employee after update", err,
			map[string]interface{}{"uuid": uuid})
	}

	publishMutationEvent(ctx, s.eventPublisher, s.config.PubSub.Topics.UpdateEmployee, "UPDATE", tenantID, resp, 1)
	observability.RecordEmployeeUpdated(ctx, tenantID, 1)

	span.SetStatus(codes.Ok, "Employee updated")
	return resp, nil
}

func (s *employeeService) HardDeleteEmployee(ctx context.Context, uuid string) error {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.delete")
	defer span.End()

	tenantID := middleware.GetRequestContextFromContext(ctx).TenantID

	span.SetAttributes(
		attribute.String("employee.uuid", uuid),
		attribute.String("tenant.id", tenantID),
	)

	s.logger.InfoWithTrace(ctx, "Deleting employee", map[string]interface{}{
		"uuid":     uuid,
		"tenantId": tenantID,
	})

	// Jurisdictions cascade automatically — the schema declares
	// `employee_id REFERENCES employee_v3(id) ON DELETE CASCADE`, so deleting
	// the employee row drops every owning jurisdiction in the same statement.
	// No explicit pre-delete loop needed (and avoiding one removes a best-effort
	// path where a single failed jurisdiction-delete used to be silently
	// logged but didn't block the employee delete from succeeding).
	if err := s.repo.Delete(ctx, uuid, tenantID); err != nil {
		return mapDownstreamErr(ctx, span, s.logger, employeeServiceName, "HardDeleteEmployee", "employee", err)
	}

	publishMutationEvent(ctx, s.eventPublisher, s.config.PubSub.Topics.DeleteEmployee, "DELETE", tenantID, map[string]string{"id": uuid}, 1)
	observability.RecordEmployeeDeleted(ctx, tenantID)

	span.SetStatus(codes.Ok, "Employee deleted")
	return nil
}

// PatchEmployee applies a partial update to an existing employee. Pointer
// fields on PatchEmployeeRequest let us distinguish "field omitted" from
// "field set to zero" — only supplied fields are written; omitted fields
// preserve their DB value.
//
// Jurisdictions has reconcile-on-set semantics: supplying an array reconciles
// the collection (id+version → update in place, id-less → insert, omitted →
// deactivate); omitting the field leaves jurisdictions untouched. The reconcile
// and the row-update share the tenantdb request transaction, so any failure
// rolls back the whole patch.
//
// Optimistic concurrency: the client sends the employee version it last read;
// a versioned CAS rejects a stale patch with 409 ROW_VERSION_MISMATCH.
func (s *employeeService) PatchEmployee(ctx context.Context, uuid string, req *models.PatchEmployeeRequest) (*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.patch")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("employee.uuid", uuid),
		attribute.String("tenant.id", tenantID),
	)

	if !req.HasAnyField() {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"empty patch body",
			errors.New(errors.CodeValidation, "at least one mutable field must be supplied for patch"),
			map[string]interface{}{"uuid": uuid})
	}

	// Load the current row for existence (clean 404) and the optimistic fast-fail
	// version check; the authoritative guard is the CAS in repo.Patch.
	existing, err := s.repo.FetchForWrite(ctx, uuid, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, employeeServiceName, "PatchEmployee", "employee", err)
	}
	if existing.Version != req.Version {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"employee version mismatch",
			errors.New(errors.CodeRowVersionMismatch, "employee was modified concurrently"),
			map[string]interface{}{"uuid": uuid})
	}
	expectedVersion := existing.Version

	// Build the persistence change-set. Nil pointers are skipped by GORM's
	// Updates(struct); the only fields that get written are the ones the
	// client actually supplied. Version (bumped) and audit columns are server-set.
	patch := &models.EmployeePatch{
		Status:       req.Status,
		EmployeeType: req.EmployeeType,
		Department:   req.Department,
		Designation:  req.Designation,
		IsActive:     req.IsActive,
		Version:      expectedVersion + 1,
		ModifiedBy:   userID,
		ModifiedTime: time.Now().UnixMilli(),
	}

	if err := s.repo.Patch(ctx, uuid, tenantID, patch, expectedVersion); err != nil {
		// Preserve the typed code — a CAS miss is ROW_VERSION_MISMATCH (409).
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to patch employee", err, map[string]interface{}{"uuid": uuid})
	}

	// Jurisdictions reconcile-on-set. nil → leave alone; non-nil → reconcile.
	if req.Jurisdictions != nil {
		if err := s.jurisdictionSvc.ReconcileJurisdictions(ctx, uuid, *req.Jurisdictions); err != nil {
			return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
				"failed to reconcile jurisdictions", err,
				map[string]interface{}{"uuid": uuid})
		}
	}

	resp, err := s.GetEmployeeByUUID(ctx, uuid)
	if err != nil {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to read employee after patch", err,
			map[string]interface{}{"uuid": uuid})
	}

	publishMutationEvent(ctx, s.eventPublisher, s.config.PubSub.Topics.UpdateEmployee, "UPDATE", tenantID, resp, 1)
	observability.RecordEmployeeUpdated(ctx, tenantID, 1)

	span.SetStatus(codes.Ok, "Employee patched")
	return resp, nil
}

// DeactivateEmployee soft-deactivates an active employee. Enforces a true
// state transition — calling deactivate on an already-inactive employee
// returns 409 EMPLOYEE_ALREADY_INACTIVE rather than silently no-op'ing, so
// audit logs reflect only meaningful transitions and double-click UIs don't
// produce spurious modifiedTime bumps.
//
// Concurrency is optimistic: the version is bumped and the write compare-and-
// swaps on it, so two racing deactivates can't both pass the state check — the
// loser's CAS finds a moved version and gets a 409.
func (s *employeeService) DeactivateEmployee(ctx context.Context, uuid string) (*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.deactivate")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("employee.uuid", uuid),
		attribute.String("tenant.id", tenantID),
	)

	s.logger.InfoWithTrace(ctx, "Deactivating employee", map[string]interface{}{
		"uuid":     uuid,
		"tenantId": tenantID,
	})

	existing, err := s.repo.FetchForWrite(ctx, uuid, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, employeeServiceName, "DeactivateEmployee", "employee", err)
	}

	if !existing.IsActive {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"employee is already inactive",
			errors.New(errors.CodeEmployeeAlreadyInactive, "employee is already inactive"),
			map[string]interface{}{"uuid": uuid})
	}

	expectedVersion := existing.Version
	existing.IsActive = false
	existing.Version = expectedVersion + 1
	existing.ModifiedBy = userID
	existing.ModifiedTime = time.Now().UnixMilli()

	if err := s.repo.Update(ctx, existing, expectedVersion); err != nil {
		// A concurrent transition moves the version → ROW_VERSION_MISMATCH (409).
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to deactivate employee", err, map[string]interface{}{"uuid": uuid})
	}

	observability.RecordEmployeeDeactivated(ctx, tenantID)
	span.SetStatus(codes.Ok, "Employee deactivated")
	return s.GetEmployeeByUUID(ctx, uuid)
}

// ReactivateEmployee restores a previously deactivated employee. Mirror of
// DeactivateEmployee — enforces the inactive → active transition and returns
// 409 EMPLOYEE_ALREADY_ACTIVE when the employee is already active. Version is
// bumped and the write compare-and-swaps on it.
func (s *employeeService) ReactivateEmployee(ctx context.Context, uuid string) (*models.EmployeeResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.employee.reactivate")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("employee.uuid", uuid),
		attribute.String("tenant.id", tenantID),
	)

	s.logger.InfoWithTrace(ctx, "Reactivating employee", map[string]interface{}{
		"uuid":     uuid,
		"tenantId": tenantID,
	})

	existing, err := s.repo.FetchForWrite(ctx, uuid, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, employeeServiceName, "ReactivateEmployee", "employee", err)
	}

	if existing.IsActive {
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"employee is already active",
			errors.New(errors.CodeEmployeeAlreadyActive, "employee is already active"),
			map[string]interface{}{"uuid": uuid})
	}

	expectedVersion := existing.Version
	existing.IsActive = true
	existing.Version = expectedVersion + 1
	existing.ModifiedBy = userID
	existing.ModifiedTime = time.Now().UnixMilli()

	if err := s.repo.Update(ctx, existing, expectedVersion); err != nil {
		// A concurrent transition moves the version → ROW_VERSION_MISMATCH (409).
		return nil, propagateOp(ctx, span, s.logger, employeeServiceName,
			"failed to reactivate employee", err, map[string]interface{}{"uuid": uuid})
	}

	observability.RecordEmployeeReactivated(ctx, tenantID)
	span.SetStatus(codes.Ok, "Employee reactivated")
	return s.GetEmployeeByUUID(ctx, uuid)
}
