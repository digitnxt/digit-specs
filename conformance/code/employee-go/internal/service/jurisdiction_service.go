package service

import (
	"context"
	stderrors "errors"
	"fmt"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"

	"github.com/google/uuid"

	"employee/internal/clients/boundary"
	"employee/internal/config"
	"employee/internal/middleware"
	"employee/internal/models"
	"employee/internal/pubsub"
	"employee/internal/repository"
	"employee/pkg/errors"
	"employee/pkg/observability"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
)

const jurisdictionServiceName = "jurisdiction-service"

type jurisdictionService struct {
	repo           repository.JurisdictionRepository
	employeeSvc    EmployeeService
	boundaryClient *boundary.Client
	config         *config.Config
	eventPublisher *pubsub.EventPublisher
	logger         *tracerobs.OTelLogger
}

func NewJurisdictionService(repo repository.JurisdictionRepository, employeeSvc EmployeeService, boundaryClient *boundary.Client, config *config.Config, eventPublisher *pubsub.EventPublisher) JurisdictionService {
	return &jurisdictionService{
		repo:           repo,
		employeeSvc:    employeeSvc,
		boundaryClient: boundaryClient,
		config:         config,
		eventPublisher: eventPublisher,
		logger:         tracerobs.GetOTelLogger(),
	}
}

func (s *jurisdictionService) CreateJurisdiction(ctx context.Context, employeeID string, req *models.CreateJurisdictionRequest) (*models.JurisdictionResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.jurisdiction.create")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("tenant.id", tenantID),
		attribute.String("employee.id", employeeID),
	)

	// boundaryRelation is required (min=1). The DTO binding enforces this for
	// direct API calls, but CreateEmployees builds this request in code from an
	// embedded jurisdiction and bypasses binding — so guard here too. Without
	// it an empty relation reaches the NOT NULL jsonb column as a Postgres
	// 23502 -> 500; this returns a clean 400 in-process instead.
	if len(req.BoundaryRelation) == 0 {
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"boundary relation is required",
			errors.New(errors.CodeValidation, "at least one boundary relation is required"),
			map[string]interface{}{"tenantId": tenantID, "employeeId": employeeID})
	}

	if err := checkDuplicateBoundaryRelations(req.BoundaryRelation); err != nil {
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"duplicate boundary relation in request", err,
			map[string]interface{}{"tenantId": tenantID})
	}

	if err := s.validateBoundaryRelations(ctx, tenantID, req.BoundaryRelation); err != nil {
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"boundary code validation failed", err,
			map[string]interface{}{"tenantId": tenantID})
	}

	now := time.Now().UnixMilli()
	jurisdiction := &models.Jurisdiction{
		ID:               uuid.New().String(),
		EmployeeID:       employeeID,
		BoundaryRelation: req.BoundaryRelation,
		IsActive:         true,
		Version:          1,
		TenantID:         tenantID,
		CreatedBy:        userID,
		ModifiedBy:       userID,
		CreatedTime:      now,
		ModifiedTime:     now,
	}
	if req.IsActive != nil {
		jurisdiction.IsActive = *req.IsActive
	}

	if err := s.repo.Create(ctx, jurisdiction); err != nil {
		// FK violation on employee_id → the client referenced an unknown
		// employee. Surface as a clean 404 instead of generic 500.
		if stderrors.Is(err, repository.ErrJurisdictionEmployeeNotFound) {
			return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
				"employee not found",
				errors.New(errors.CodeNotFound, "employee not found"),
				map[string]interface{}{"tenantId": tenantID, "employeeId": employeeID})
		}
		return nil, failOp(ctx, span, s.logger, jurisdictionServiceName,
			errors.CodeDatabase,
			"failed to create jurisdiction", err,
			map[string]interface{}{"tenantId": tenantID})
	}

	resp := toJurisdictionResponse(jurisdiction)

	publishMutationEvent(ctx, s.eventPublisher, s.config.PubSub.Topics.CreateJurisdiction, "CREATE", tenantID, resp, 1)
	observability.RecordJurisdictionCreated(ctx, tenantID)

	span.SetStatus(codes.Ok, "Jurisdiction created")
	return resp, nil
}

// checkDuplicateBoundaryRelations rejects requests that include the same
// (code, boundaryType, hierarchyType) triple more than once. Duplicate
// detection is a structural check that runs unconditionally on every
// create/update — it does not call the external boundary service.
//
// BoundaryRef is a comparable struct (all string fields) and is therefore
// usable as a map key directly.
func checkDuplicateBoundaryRelations(refs []models.BoundaryRef) error {
	seen := make(map[models.BoundaryRef]struct{}, len(refs))
	for _, r := range refs {
		if _, dup := seen[r]; dup {
			return errors.New(errors.CodeValidation,
				fmt.Sprintf("duplicate boundary relation: code=%s, boundaryType=%s, hierarchyType=%s",
					r.Code, r.BoundaryType, r.HierarchyType),
			)
		}
		seen[r] = struct{}{}
	}
	return nil
}

// validateBoundaryRelations groups the requested BoundaryRefs by
// (hierarchyType, boundaryType) and validates each group against the boundary
// service's relationship search API. A code is only valid if the relationship
// API returns it under the exact hierarchyType/boundaryType requested.
//
// Failure modes are distinguished by who is at fault:
//   - Boundary service unreachable / 5xx → DOWNSTREAM_ERROR (502 — retry).
//   - Service answered but the supplied code is unknown under the requested
//     hierarchy/type → VALIDATION_ERROR (400 — client referenced a bad code).
//   - Request itself is missing one of the three required fields →
//     VALIDATION_ERROR (400 — structural).
func (s *jurisdictionService) validateBoundaryRelations(ctx context.Context, tenantID string, refs []models.BoundaryRef) error {
	// Dependency flag: when boundary is disabled we persist without validating boundary codes.
	if !s.config.Boundary.Enabled {
		return nil
	}
	if len(refs) == 0 {
		return nil
	}

	type groupKey struct {
		hierarchyType string
		boundaryType  string
	}
	grouped := make(map[groupKey][]string)
	for _, r := range refs {
		if r.Code == "" || r.BoundaryType == "" || r.HierarchyType == "" {
			return errors.New(errors.CodeValidation, "boundary relation requires code, boundaryType and hierarchyType")
		}
		k := groupKey{hierarchyType: r.HierarchyType, boundaryType: r.BoundaryType}
		grouped[k] = append(grouped[k], r.Code)
	}

	var invalid []string
	for k, codes := range grouped {
		found, err := s.boundaryClient.SearchRelationship(ctx, tenantID, k.hierarchyType, k.boundaryType, codes)
		if err != nil {
			return errors.New(errors.CodeDownstream, "boundary service lookup failed")
		}
		for _, code := range codes {
			if !found[code] {
				invalid = append(invalid, fmt.Sprintf("%s (boundaryType=%s, hierarchyType=%s)", code, k.boundaryType, k.hierarchyType))
			}
		}
	}

	if len(invalid) > 0 {
		return errors.New(errors.CodeValidation,
			fmt.Sprintf("invalid boundary relations for tenant %s: %s", tenantID, strings.Join(invalid, ", ")),
		)
	}

	return nil
}

func (s *jurisdictionService) GetJurisdictionByUUID(ctx context.Context, employeeID, jurisUUID string) (*models.JurisdictionResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.jurisdiction.get_by_uuid")
	defer span.End()

	tenantID := middleware.GetRequestContextFromContext(ctx).TenantID

	span.SetAttributes(
		attribute.String("jurisdiction.uuid", jurisUUID),
		attribute.String("employee.id", employeeID),
		attribute.String("tenant.id", tenantID),
	)

	jurisdiction, err := s.repo.FindByUUID(ctx, jurisUUID, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, jurisdictionServiceName, "GetJurisdictionByUUID", "jurisdiction", err)
	}

	// Ownership check — under the nested path, a jurisdiction that exists but
	// belongs to a different employee is "not found" at this URL. Returning 403
	// would leak the existence of the resource; 404 keeps the contract clean.
	if employeeID != "" && jurisdiction.EmployeeID != employeeID {
		span.SetStatus(codes.Ok, "Jurisdiction does not belong to employee")
		return nil, errors.ErrNotFound.WithDescription("jurisdiction not found for this employee")
	}

	span.SetStatus(codes.Ok, "Jurisdiction retrieved")
	return toJurisdictionResponse(jurisdiction), nil
}

func (s *jurisdictionService) SearchJurisdictions(ctx context.Context, employeeID string, criteria *models.JurisdictionSearchCriteria) ([]*models.JurisdictionResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.jurisdiction.search")
	defer span.End()

	tenantID := middleware.GetRequestContextFromContext(ctx).TenantID

	span.SetAttributes(
		attribute.String("tenant.id", tenantID),
		attribute.String("employee.id", employeeID),
	)

	jurisdictions, err := s.repo.Search(ctx, tenantID, employeeID, criteria)
	if err != nil {
		return nil, failOp(ctx, span, s.logger, jurisdictionServiceName,
			errors.CodeDatabase,
			"failed to search jurisdictions", err,
			map[string]interface{}{"tenantId": tenantID})
	}

	responses := make([]*models.JurisdictionResponse, 0, len(jurisdictions))
	for _, j := range jurisdictions {
		responses = append(responses, toJurisdictionResponse(j))
	}

	observability.RecordJurisdictionSearched(ctx, tenantID, len(responses))

	span.SetAttributes(attribute.Int("jurisdiction.found_count", len(responses)))
	span.SetStatus(codes.Ok, "Jurisdictions searched")
	return responses, nil
}

func toJurisdictionResponse(j *models.Jurisdiction) *models.JurisdictionResponse {
	if j == nil {
		return nil
	}
	return &models.JurisdictionResponse{
		ID:               j.ID,
		EmployeeID:       j.EmployeeID,
		BoundaryRelation: j.BoundaryRelation,
		IsActive:         j.IsActive,
		Version:          j.Version,
		AuditDetail: models.AuditDetail{
			CreatedBy:    j.CreatedBy,
			ModifiedBy:   j.ModifiedBy,
			CreatedTime:  j.CreatedTime,
			ModifiedTime: j.ModifiedTime,
		},
	}
}

func (s *jurisdictionService) DeleteJurisdiction(ctx context.Context, jurisUUID string) error {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.jurisdiction.delete")
	defer span.End()

	tenantID := middleware.GetRequestContextFromContext(ctx).TenantID

	span.SetAttributes(
		attribute.String("jurisdiction.uuid", jurisUUID),
		attribute.String("tenant.id", tenantID),
	)

	if _, err := s.repo.FindByUUID(ctx, jurisUUID, tenantID); err != nil {
		return mapDownstreamErr(ctx, span, s.logger, jurisdictionServiceName, "DeleteJurisdiction", "jurisdiction", err)
	}

	if err := s.repo.Delete(ctx, jurisUUID, tenantID); err != nil {
		return failOp(ctx, span, s.logger, jurisdictionServiceName,
			errors.CodeDatabase,
			"failed to delete jurisdiction", err,
			map[string]interface{}{"uuid": jurisUUID})
	}

	span.SetStatus(codes.Ok, "Jurisdiction deleted")
	return nil
}

func (s *jurisdictionService) UpdateJurisdiction(ctx context.Context, employeeID, jurisUUID string, req *models.UpdateJurisdictionRequest) (*models.JurisdictionResponse, error) {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.jurisdiction.update")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("jurisdiction.uuid", jurisUUID),
		attribute.String("employee.id", employeeID),
		attribute.String("tenant.id", tenantID),
	)

	s.logger.InfoWithTrace(ctx, "Updating jurisdiction", map[string]interface{}{
		"uuid":     jurisUUID,
		"tenantId": tenantID,
	})

	existing, err := s.repo.FindByUUID(ctx, jurisUUID, tenantID)
	if err != nil {
		return nil, mapDownstreamErr(ctx, span, s.logger, jurisdictionServiceName, "UpdateJurisdiction", "jurisdiction", err)
	}

	// Ownership check — updating a jurisdiction at a path that doesn't own it
	// is treated as 404, matching GET behaviour and keeping the nested
	// contract honest.
	if employeeID != "" && existing.EmployeeID != employeeID {
		span.SetStatus(codes.Ok, "Jurisdiction does not belong to employee")
		return nil, errors.ErrNotFound.WithDescription("jurisdiction not found for this employee")
	}

	// Optimistic fast-fail: reject a stale write before boundary validation. The
	// authoritative guard is the CAS in repo.Update.
	if existing.Version != req.Version {
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"jurisdiction version mismatch",
			errors.New(errors.CodeRowVersionMismatch, "jurisdiction was modified concurrently"),
			map[string]interface{}{"uuid": jurisUUID})
	}

	// boundaryRelation is required on the PUT body (validated at bind time).
	if err := checkDuplicateBoundaryRelations(req.BoundaryRelation); err != nil {
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"duplicate boundary relation in request", err,
			map[string]interface{}{"tenantId": tenantID})
	}
	if err := s.validateBoundaryRelations(ctx, tenantID, req.BoundaryRelation); err != nil {
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"boundary code validation failed", err,
			map[string]interface{}{"tenantId": tenantID})
	}

	// PUT-style overwrite: build the next entity state from the loaded row +
	// the request body. Immutable fields are carried forward inside ToEntity;
	// bump the version for the compare-and-swap.
	expectedVersion := existing.Version
	updated := req.ToEntity(userID, *existing)
	updated.Version = expectedVersion + 1

	if err := s.repo.Update(ctx, &updated, expectedVersion); err != nil {
		// Preserve the typed code — a CAS miss is ROW_VERSION_MISMATCH (409), not
		// a DATABASE_ERROR (500). propagateOp keeps whatever code the repo set.
		return nil, propagateOp(ctx, span, s.logger, jurisdictionServiceName,
			"failed to update jurisdiction", err,
			map[string]interface{}{"uuid": jurisUUID})
	}

	resp, err := s.GetJurisdictionByUUID(ctx, employeeID, jurisUUID)
	if err != nil {
		return nil, failOp(ctx, span, s.logger, jurisdictionServiceName,
			errors.CodeDatabase,
			"failed to build response", err, nil)
	}

	publishMutationEvent(ctx, s.eventPublisher, s.config.PubSub.Topics.UpdateJurisdiction, "UPDATE", tenantID, resp, 1)
	observability.RecordJurisdictionUpdated(ctx, tenantID)

	span.SetStatus(codes.Ok, "Jurisdiction updated")
	return resp, nil
}

// ReconcileJurisdictions applies an employee PUT/PATCH's jurisdiction array to
// the employee's collection as a three-way diff (see VERSIONING-DESIGN.md §3):
//
//   - item with id + version → update in place (UpdateJurisdiction does the
//     ownership + version check + bump + boundary validation)
//   - item with id but no version → 400 (version required to update an existing
//     jurisdiction)
//   - item with id not owned by this employee → 404 (rejected)
//   - item with no id → insert new (version 1)
//   - existing jurisdiction omitted from the array → deactivated (is_active=false)
//
// An empty array deactivates the whole collection. Everything runs inside the
// surrounding request transaction, so any failure rolls back the whole employee
// update. Errors are returned unwrapped so the caller's propagateOp preserves
// the typed code (a bad-boundary VALIDATION_ERROR stays a 400, a stale
// jurisdiction stays a 409, etc.).
func (s *jurisdictionService) ReconcileJurisdictions(ctx context.Context, employeeID string, fresh []*models.Jurisdiction) error {
	tracer := otel.Tracer(employeeServiceName)
	ctx, span := tracer.Start(ctx, "service.jurisdiction.reconcile")
	defer span.End()

	reqCtx := middleware.GetRequestContextFromContext(ctx)
	tenantID, userID := reqCtx.TenantID, reqCtx.UserID

	span.SetAttributes(
		attribute.String("employee.id", employeeID),
		attribute.Int("jurisdiction.count", len(fresh)),
	)

	// Load the current collection to resolve supplied ids and detect omissions.
	// Empty criteria → no limit, no active filter (inactive rows are returned too
	// but DeactivateOmitted only touches active ones, so they are left alone).
	existing, err := s.SearchJurisdictions(ctx, employeeID, &models.JurisdictionSearchCriteria{})
	if err != nil {
		return err
	}
	existingByID := make(map[string]struct{}, len(existing))
	for _, j := range existing {
		existingByID[j.ID] = struct{}{}
	}

	keep := make([]string, 0, len(fresh))
	for _, j := range fresh {
		// id-less → insert new (version 1). Add the new id to keep so the
		// deactivate-omitted sweep below doesn't immediately deactivate it.
		if j.ID == "" {
			jurisReq := &models.CreateJurisdictionRequest{
				BoundaryRelation: j.BoundaryRelation,
				IsActive:         &j.IsActive,
			}
			created, err := s.CreateJurisdiction(ctx, employeeID, jurisReq)
			if err != nil {
				return err
			}
			keep = append(keep, created.ID)
			continue
		}
		// id present but not owned by this employee → reject (anti-tampering).
		if _, ok := existingByID[j.ID]; !ok {
			return errors.New(errors.CodeNotFound, "jurisdiction not found for this employee: "+j.ID)
		}
		// id present but version missing → 400. Updating an existing row requires
		// proving the current version (id + version travel together).
		if j.Version <= 0 {
			return errors.New(errors.CodeValidation, "version is required to update an existing jurisdiction: "+j.ID)
		}
		// Update in place — delegates the version check + bump + boundary
		// validation to UpdateJurisdiction.
		upReq := &models.UpdateJurisdictionRequest{
			BoundaryRelation: j.BoundaryRelation,
			IsActive:         &j.IsActive,
			Version:          j.Version,
		}
		if _, err := s.UpdateJurisdiction(ctx, employeeID, j.ID, upReq); err != nil {
			return err
		}
		keep = append(keep, j.ID)
	}

	// Deactivate any active jurisdiction the client left out of the array.
	if err := s.repo.DeactivateOmitted(ctx, employeeID, tenantID, userID, keep); err != nil {
		return failOp(ctx, span, s.logger, jurisdictionServiceName,
			errors.CodeDatabase, "failed to deactivate omitted jurisdictions", err,
			map[string]interface{}{"tenantId": tenantID, "employeeId": employeeID})
	}

	span.SetStatus(codes.Ok, "Jurisdictions reconciled")
	return nil
}
