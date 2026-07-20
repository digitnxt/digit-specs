package service

import (
	"context"
	"errors"

	"individual/internal/common"
	"individual/internal/config"
	"individual/internal/models"
	"individual/internal/pubsub"
	"individual/internal/repository"
	"individual/pkg/observability"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	"github.com/rs/zerolog/log"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

// IndividualService handles core business logic. Validation is owned by the
// handler layer — service methods assume input has already passed the
// validator before reaching here.
type IndividualService interface {
	CreateIndividual(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) (*models.Individual, error)
	UpdateIndividual(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) (*models.Individual, error)
	DeleteIndividual(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) (*models.Individual, error)
	SearchIndividuals(ctx context.Context, request *models.IndividualSearchRequest, tenantID string) ([]models.Individual, int64, error)
	IndividualExists(ctx context.Context, criteria *models.SearchCriteria, tenantID string, includeDeleted bool) (bool, error)
}

type individualService struct {
	repo              repository.IndividualRepository
	enrichmentService EnrichmentService
	encryptionService EncryptionService
	eventPublisher    *pubsub.EventPublisher
	config            *config.Config
}

// NewIndividualService creates a new individual service.
func NewIndividualService(
	repo repository.IndividualRepository,
	enrichmentService EnrichmentService,
	encryptionService EncryptionService,
	eventPublisher *pubsub.EventPublisher,
	cfg *config.Config,
) IndividualService {
	return &individualService{
		repo:              repo,
		enrichmentService: enrichmentService,
		encryptionService: encryptionService,
		eventPublisher:    eventPublisher,
		config:            cfg,
	}
}

// CreateIndividual creates a new individual.
func (s *individualService) CreateIndividual(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) (*models.Individual, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.individual.create")
	defer span.End()
	span.SetAttributes(attribute.String("tenant.id", reqContext.TenantID))

	log.Debug().Ctx(ctx).Str("tenantID", reqContext.TenantID).Str("userID", reqContext.UserID).Msg("create individual: start")

	if err := s.enrichmentService.EnrichForCreate(ctx, individual, reqContext); err != nil {
		log.Error().Err(err).Ctx(ctx).Str("tenantID", reqContext.TenantID).Msg("create individual: enrichment failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "enrichment failed")
		tracerobs.RecordError(ctx, "enrich_for_create_error", "individual-service")
		return nil, err
	}

	if err := s.encryptionService.EncryptIndividual(ctx, individual); err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("create individual: encryption failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "encryption failed")
		tracerobs.RecordError(ctx, "encrypt_individual_error", "individual-service")
		return nil, err
	}

	if err := s.repo.Create(ctx, individual); err != nil {
		// Unique-constraint violation → 409, translated in the repository layer; propagate as-is.
		if errors.Is(err, common.ErrDuplicate) {
			span.SetStatus(codes.Error, "duplicate")
			return nil, err
		}
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("create individual: persist failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "persist failed")
		tracerobs.RecordError(ctx, "create_individual_db_error", "individual-service")
		return nil, common.ErrDatabase.WithContext(map[string]interface{}{
			"operation": "create",
			"error":     err.Error(),
		})
	}

	span.SetAttributes(attribute.String("individual.id", individual.ID))
	observability.RecordIndividualCreated(ctx, reqContext.TenantID, 1)

	pubSpan := trace.SpanFromContext(ctx)
	s.eventPublisher.PublishEvent(ctx, pubSpan, s.config.PubSub.Topics.CreateIndividual, s.config.PubSub.Topics.CreateIndividual, reqContext.TenantID, reqContext.UserID, individual, 1)

	if err := s.encryptionService.DecryptIndividual(ctx, individual); err != nil {
		log.Warn().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("create individual: decrypt-for-response failed (returning encrypted values)")
	}

	span.SetStatus(codes.Ok, "")
	log.Info().Ctx(ctx).Str("individualID", individual.ID).Str("individualExternalID", individual.IndividualID).Str("tenantID", individual.TenantID).Msg("individual created")
	return individual, nil
}

// UpdateIndividual updates an existing individual
func (s *individualService) UpdateIndividual(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) (*models.Individual, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.individual.update")
	defer span.End()
	span.SetAttributes(
		attribute.String("tenant.id", reqContext.TenantID),
		attribute.String("individual.id", individual.ID),
	)

	log.Debug().Ctx(ctx).Str("individualID", individual.ID).Str("tenantID", reqContext.TenantID).Msg("update individual: start")

	existing, err := s.repo.FindByID(ctx, individual.ID, reqContext.TenantID)
	if err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: lookup failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "lookup failed")
		tracerobs.RecordError(ctx, "update_individual_lookup_error", "individual-service")
		return nil, err
	}
	if existing == nil {
		log.Info().Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: target not found")
		span.SetStatus(codes.Error, "individual not found")
		return nil, common.ErrNonExistentEntity.WithContext(map[string]interface{}{
			"id": individual.ID,
		})
	}

	// Optimistic-concurrency fast-fail: reject an obviously stale write up front,
	// before doing enrichment/encryption work. The authoritative guard is the
	// version-checked update (CAS) in the repository, which also closes the race
	// in the read→write window.
	if existing.RowVersion != individual.RowVersion {
		log.Warn().Ctx(ctx).
			Str("individualID", individual.ID).
			Int("expectedVersion", existing.RowVersion).
			Int("providedVersion", individual.RowVersion).
			Msg("update individual: version mismatch")
		span.SetStatus(codes.Error, "row version mismatch")
		return nil, common.ErrRowVersionMismatch.WithContext(map[string]interface{}{
			"expected": existing.RowVersion,
			"provided": individual.RowVersion,
		})
	}

	// Reconcile children against what already exists: resolve id-less identifiers
	// by type (so a re-sent AADHAAR updates in place), and reject any child id that
	// isn't an existing active child of this individual (no cross-individual
	// reassignment/modification).
	if err := reconcileChildren(individual, existing); err != nil {
		span.SetStatus(codes.Error, "child reconciliation failed")
		return nil, err
	}

	// Preserve only server-managed fields that the client must not overwrite.
	// PUT semantics: all other fields come from the request body.
	if individual.IndividualID == "" {
		individual.IndividualID = existing.IndividualID
	}
	if individual.TenantID == "" {
		individual.TenantID = existing.TenantID
	}
	individual.Active = existing.Active
	if individual.CreatedBy == "" {
		individual.CreatedBy = existing.CreatedBy
	}
	if individual.CreatedTime == 0 {
		individual.CreatedTime = existing.CreatedTime
	}
	// additionalDetails: full-replace (PUT) — whatever the client sent (including
	// absent, i.e. null) becomes the new value; existing is not merged in.

	// Capture the client-supplied version before enrichment bumps it; the repo
	// uses it as the optimistic guard (compare-and-swap) on the update.
	expectedVersion := individual.RowVersion

	if err := s.enrichmentService.EnrichForUpdate(ctx, individual, reqContext); err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: enrichment failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "enrichment failed")
		tracerobs.RecordError(ctx, "update_individual_enrich_error", "individual-service")
		return nil, err
	}

	if err := s.encryptionService.EncryptIndividual(ctx, individual); err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: encryption failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "encryption failed")
		tracerobs.RecordError(ctx, "update_individual_encrypt_error", "individual-service")
		return nil, err
	}

	if err := s.repo.Update(ctx, individual, expectedVersion); err != nil {
		if errors.Is(err, common.ErrOptimisticLock) {
			// Lost the race: the row was updated by someone else between our read
			// and our write — same outcome as a stale client version.
			log.Warn().Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: version conflict on write")
			span.SetStatus(codes.Error, "row version mismatch")
			return nil, common.ErrRowVersionMismatch.WithContext(map[string]interface{}{
				"id": individual.ID,
			})
		}
		if errors.Is(err, common.ErrDuplicate) {
			span.SetStatus(codes.Error, "duplicate")
			return nil, err
		}
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: persist failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "persist failed")
		tracerobs.RecordError(ctx, "update_individual_db_error", "individual-service")
		return nil, common.ErrDatabase.WithContext(map[string]interface{}{
			"operation": "update",
			"error":     err.Error(),
		})
	}

	// Re-fetch so the response carries the full record (identifiers,
	// addresses, documents) — the request body might not have included
	// these and the repo doesn't delete missing children, so the in-memory
	// `individual` is incomplete after Update. See bug.md #6/#17.
	refreshed, err := s.repo.FindByID(ctx, individual.ID, reqContext.TenantID)
	if err == nil && refreshed != nil {
		individual = refreshed
	}

	observability.RecordIndividualUpdated(ctx, reqContext.TenantID, 1)

	pubSpan := trace.SpanFromContext(ctx)
	s.eventPublisher.PublishEvent(ctx, pubSpan, s.config.PubSub.Topics.UpdateIndividual, s.config.PubSub.Topics.UpdateIndividual, reqContext.TenantID, reqContext.UserID, individual, individual.RowVersion)

	if err := s.encryptionService.DecryptIndividual(ctx, individual); err != nil {
		log.Warn().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("update individual: decrypt-for-response failed (returning encrypted values)")
	}

	span.SetStatus(codes.Ok, "")
	log.Info().Ctx(ctx).Str("individualID", individual.ID).Int("version", individual.RowVersion).Msg("individual updated")
	return individual, nil
}

// DeleteIndividual soft deletes an individual
func (s *individualService) DeleteIndividual(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) (*models.Individual, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.individual.delete")
	defer span.End()
	span.SetAttributes(
		attribute.String("tenant.id", reqContext.TenantID),
		attribute.String("individual.id", individual.ID),
	)

	log.Debug().Ctx(ctx).Str("individualID", individual.ID).Str("tenantID", reqContext.TenantID).Msg("delete individual: start")

	existing, err := s.repo.FindByID(ctx, individual.ID, reqContext.TenantID)
	if err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("delete individual: lookup failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "lookup failed")
		tracerobs.RecordError(ctx, "delete_individual_lookup_error", "individual-service")
		return nil, err
	}
	if existing == nil {
		log.Info().Ctx(ctx).Str("individualID", individual.ID).Msg("delete individual: target not found")
		span.SetStatus(codes.Error, "individual not found")
		return nil, common.ErrNonExistentEntity.WithContext(map[string]interface{}{
			"id": individual.ID,
		})
	}

	if err := s.enrichmentService.EnrichForDelete(ctx, existing, reqContext); err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("delete individual: enrichment failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "enrichment failed")
		tracerobs.RecordError(ctx, "delete_individual_enrich_error", "individual-service")
		return nil, err
	}

	if err := s.repo.Delete(ctx, individual.ID, reqContext.TenantID); err != nil {
		log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Msg("delete individual: persist failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "persist failed")
		tracerobs.RecordError(ctx, "delete_individual_db_error", "individual-service")
		return nil, common.ErrDatabase.WithContext(map[string]interface{}{
			"operation": "delete",
			"error":     err.Error(),
		})
	}

	observability.RecordIndividualDeleted(ctx, reqContext.TenantID, 1)

	pubSpan := trace.SpanFromContext(ctx)
	s.eventPublisher.PublishEvent(ctx, pubSpan, s.config.PubSub.Topics.DeleteIndividual, s.config.PubSub.Topics.DeleteIndividual, reqContext.TenantID, reqContext.UserID, existing, 1)

	span.SetStatus(codes.Ok, "")
	log.Info().Ctx(ctx).Str("individualID", individual.ID).Msg("individual deleted (soft)")
	return existing, nil
}

// SearchIndividuals searches for individuals
func (s *individualService) SearchIndividuals(ctx context.Context, request *models.IndividualSearchRequest, tenantID string) ([]models.Individual, int64, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.individual.search")
	defer span.End()
	span.SetAttributes(
		attribute.String("tenant.id", tenantID),
		attribute.Int("page", request.Page),
		attribute.Int("size", request.Size),
	)

	log.Debug().Ctx(ctx).Str("tenantID", tenantID).Int("page", request.Page).Int("size", request.Size).Bool("includeDeleted", request.IncludeDeleted).Msg("search individuals: start")

	// Hash plaintext mobile numbers before querying
	if request.Individual != nil && len(request.Individual.MobileNumber) > 0 {
		hashed := make([]string, 0, len(request.Individual.MobileNumber))
		for _, m := range request.Individual.MobileNumber {
			if m == "" {
				continue
			}
			h, err := s.encryptionService.HashMobileNumber(m)
			if err != nil {
				log.Error().Err(err).Ctx(ctx).Msg("search individuals: failed to hash mobile number")
				span.RecordError(err)
				span.SetStatus(codes.Error, "failed to hash mobile number")
				tracerobs.RecordError(ctx, "search_individual_hash_error", "individual-service")
				return nil, 0, common.ErrFailedToHash.WithContext(map[string]interface{}{"error": err.Error()})
			}
			hashed = append(hashed, h)
		}
		request.Individual.MobileNumber = hashed
	}

	// Defensive defaults/clamps
	page := request.Page
	if page < 1 {
		page = common.DefaultPage
	}
	size := request.Size
	if size <= 0 {
		size = common.DefaultPageSize
	}
	if size > common.MaxPageSize {
		size = common.MaxPageSize
	}

	individuals, totalCount, err := s.repo.Search(ctx, request.Individual, tenantID, page, size, request.IncludeDeleted)
	if err != nil {
		log.Error().Err(err).Ctx(ctx).Str("tenantID", tenantID).Msg("search individuals: repo failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "repo search failed")
		tracerobs.RecordError(ctx, "search_individual_db_error", "individual-service")
		return nil, 0, common.ErrDatabase.WithContext(map[string]interface{}{
			"operation": "search",
			"error":     err.Error(),
		})
	}

	if err := s.encryptionService.DecryptIndividuals(ctx, individuals); err != nil {
		log.Warn().Err(err).Ctx(ctx).Msg("search individuals: decrypt-for-response failed on one or more rows")
	}

	observability.RecordIndividualSearched(ctx, tenantID, len(individuals))
	span.SetAttributes(attribute.Int64("result.count", totalCount))
	span.SetStatus(codes.Ok, "")
	log.Info().Ctx(ctx).Str("tenantID", tenantID).Int("page", page).Int("size", size).Int("returned", len(individuals)).Int64("total", totalCount).Msg("search individuals: done")
	return individuals, totalCount, nil
}

// IndividualExists returns true if at least one record matches the criteria.
// Same validation and mobile-hashing pipeline as SearchIndividuals.
func (s *individualService) IndividualExists(ctx context.Context, criteria *models.SearchCriteria, tenantID string, includeDeleted bool) (bool, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.individual.exists")
	defer span.End()
	span.SetAttributes(attribute.String("tenant.id", tenantID))

	log.Debug().Ctx(ctx).Str("tenantID", tenantID).Bool("includeDeleted", includeDeleted).Msg("exists: start")

	if criteria != nil && len(criteria.MobileNumber) > 0 {
		hashed := make([]string, 0, len(criteria.MobileNumber))
		for _, m := range criteria.MobileNumber {
			if m == "" {
				continue
			}
			h, err := s.encryptionService.HashMobileNumber(m)
			if err != nil {
				log.Error().Err(err).Ctx(ctx).Msg("exists: failed to hash mobile number")
				span.RecordError(err)
				span.SetStatus(codes.Error, "failed to hash mobile number")
				tracerobs.RecordError(ctx, "exists_individual_hash_error", "individual-service")
				return false, common.ErrFailedToHash.WithContext(map[string]interface{}{"error": err.Error()})
			}
			hashed = append(hashed, h)
		}
		criteria.MobileNumber = hashed
	}

	exists, err := s.repo.Exists(ctx, criteria, tenantID, includeDeleted)
	if err != nil {
		log.Error().Err(err).Ctx(ctx).Str("tenantID", tenantID).Msg("exists: repo failed")
		span.RecordError(err)
		span.SetStatus(codes.Error, "repo exists failed")
		tracerobs.RecordError(ctx, "exists_individual_db_error", "individual-service")
		return false, common.ErrDatabase.WithContext(map[string]interface{}{
			"operation": "exists",
			"error":     err.Error(),
		})
	}
	span.SetAttributes(attribute.Bool("result.exists", exists))
	span.SetStatus(codes.Ok, "")
	log.Debug().Ctx(ctx).Str("tenantID", tenantID).Bool("exists", exists).Msg("exists: done")
	return exists, nil
}

// reconcileChildren aligns a PUT request's children with the individual's existing
// active children, enforcing safe full-replace semantics:
//   - B14 identifier match-by-type: an id-less identifier whose identifierType
//     matches an existing active one adopts that id, so it updates in place
//     (stable id) rather than inserting a duplicate that would hit the
//     (individualId, identifierType) unique index.
//   - B15 ownership: any child carrying an id that is NOT an existing active child
//     of this individual is rejected — a PUT must never reassign or modify another
//     individual's child. (documents/addresses have no natural key, so an id-less
//     one is always a new row.)
func reconcileChildren(individual, existing *models.Individual) error {
	identByType := make(map[string]string, len(existing.Identifiers))
	identIDs := make(map[string]bool, len(existing.Identifiers))
	for i := range existing.Identifiers {
		identByType[existing.Identifiers[i].IdentifierType] = existing.Identifiers[i].ID
		identIDs[existing.Identifiers[i].ID] = true
	}
	addrIDs := make(map[string]bool, len(existing.Addresses))
	for i := range existing.Addresses {
		addrIDs[existing.Addresses[i].ID] = true
	}
	docIDs := make(map[string]bool, len(existing.Documents))
	for i := range existing.Documents {
		docIDs[existing.Documents[i].ID] = true
	}

	// B14: resolve id-less identifiers to the existing active one of the same type.
	for i := range individual.Identifiers {
		if individual.Identifiers[i].ID == "" {
			if id, ok := identByType[individual.Identifiers[i].IdentifierType]; ok {
				individual.Identifiers[i].ID = id
			}
		}
	}

	// B15: a supplied child id must belong to this individual.
	for i := range individual.Identifiers {
		if id := individual.Identifiers[i].ID; id != "" && !identIDs[id] {
			return common.ErrValidation.WithContext(map[string]interface{}{
				"field":   "identifiers",
				"message": "identifier id does not belong to this individual: " + id,
			})
		}
	}
	for i := range individual.Addresses {
		if id := individual.Addresses[i].ID; id != "" && !addrIDs[id] {
			return common.ErrValidation.WithContext(map[string]interface{}{
				"field":   "address",
				"message": "address id does not belong to this individual: " + id,
			})
		}
	}
	for i := range individual.Documents {
		if id := individual.Documents[i].ID; id != "" && !docIDs[id] {
			return common.ErrValidation.WithContext(map[string]interface{}{
				"field":   "documents",
				"message": "document id does not belong to this individual: " + id,
			})
		}
	}
	return nil
}
