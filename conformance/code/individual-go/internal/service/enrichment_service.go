package service

import (
	"context"

	"individual/internal/clients"
	"individual/internal/common"
	"individual/internal/config"
	"individual/internal/models"

	"github.com/rs/zerolog/log"
)

// EnrichmentService handles data enrichment
type EnrichmentService interface {
	EnrichForCreate(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) error
	EnrichForUpdate(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) error
	EnrichForDelete(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) error
}

type enrichmentService struct {
	idgenClient clients.IDGenClient
	config      *config.IDGenConfig
}

// NewEnrichmentService creates a new enrichment service
func NewEnrichmentService(idgenClient clients.IDGenClient, config *config.IDGenConfig) EnrichmentService {
	return &enrichmentService{
		idgenClient: idgenClient,
		config:      config,
	}
}

// EnrichForCreate enriches individual for create operation
func (s *enrichmentService) EnrichForCreate(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) error {
	now := common.GetCurrentTimestamp()

	// Always generate a new UUID — id is server-managed and must not be
	// supplied by the client.
	individual.ID = common.GenerateUUID()

	// Always generate individualId via IDGen — readOnly per spec.
	customVars := map[string]string{"ORG": individual.TenantID}
	ids, err := s.idgenClient.GenerateIDs(ctx, individual.TenantID, s.config.Format, 1, customVars)
	if err != nil {
		log.Error().Err(err).Ctx(ctx).Str("tenantID", individual.TenantID).Msg("IDGen failed to generate individualId")
		// idgen is a downstream dependency — surface as DOWNSTREAM_ERROR (502) with the
		// specific cause, not the generic DATABASE_ERROR/500 catch-all.
		return common.ErrDownstream.WithContext(map[string]interface{}{
			"message": "failed to generate individualId: " + err.Error(),
		})
	}
	if len(ids) > 0 {
		individual.IndividualID = ids[0]
	} else {
		individual.IndividualID = "IND-" + common.GenerateUUID()[:8]
	}

	// Set audit fields (using clientID from header)
	if reqContext != nil {
		individual.CreatedBy = reqContext.UserID
		individual.ModifiedBy = reqContext.UserID
		individual.RequestID = reqContext.RequestID
	}
	individual.CreatedTime = now
	individual.ModifiedTime = now
	individual.RowVersion = 1
	// Newly created individuals should be active by default
	individual.Active = true

	// Enrich addresses
	for i := range individual.Addresses {
		if individual.Addresses[i].ID == "" {
			individual.Addresses[i].ID = common.GenerateUUID()
		}
		individual.Addresses[i].TenantID = individual.TenantID
		if reqContext != nil {
			individual.Addresses[i].CreatedBy = reqContext.UserID
			individual.Addresses[i].ModifiedBy = reqContext.UserID
			individual.Addresses[i].RequestID = reqContext.RequestID
		}
		individual.Addresses[i].CreatedTime = now
		individual.Addresses[i].ModifiedTime = now
	}

	// Enrich identifiers
	for i := range individual.Identifiers {
		if individual.Identifiers[i].ID == "" {
			individual.Identifiers[i].ID = common.GenerateUUID()
		}
		if reqContext != nil {
			individual.Identifiers[i].CreatedBy = reqContext.UserID
			individual.Identifiers[i].ModifiedBy = reqContext.UserID
			individual.Identifiers[i].RequestID = reqContext.RequestID
		}
		individual.Identifiers[i].CreatedTime = now
		individual.Identifiers[i].ModifiedTime = now
	}

	// NOTE: We do NOT auto-create a SYSTEM_GENERATED identifier when the
	// caller sends none. SYSTEM_GENERATED is just one of the allowed enum
	// values in identifierType — there is no spec business rule that mandates
	// auto-creation, and individualId (the IDGen-generated IND-XXXXX) already
	// covers the "stable cross-reference id" use case. See bug.md #1.

	// Enrich documents
	for i := range individual.Documents {
		if individual.Documents[i].ID == "" {
			individual.Documents[i].ID = common.GenerateUUID()
		}
		individual.Documents[i].IndividualID = individual.ID
		if reqContext != nil {
			individual.Documents[i].CreatedBy = reqContext.UserID
			individual.Documents[i].ModifiedBy = reqContext.UserID
			individual.Documents[i].RequestID = reqContext.RequestID
		}
		individual.Documents[i].CreatedTime = now
		individual.Documents[i].ModifiedTime = now
	}

	return nil
}

// EnrichForUpdate enriches individual for update operation
func (s *enrichmentService) EnrichForUpdate(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) error {
	now := common.GetCurrentTimestamp()

	// Update audit fields (using clientID from header)
	if reqContext != nil {
		individual.ModifiedBy = reqContext.UserID
		individual.RequestID = reqContext.RequestID
	}
	individual.ModifiedTime = now

	// Increment row version for optimistic locking
	individual.RowVersion++

	// Enrich addresses (present in the request => active under PUT full-replace)
	for i := range individual.Addresses {
		individual.Addresses[i].Active = true
		if individual.Addresses[i].ID == "" {
			individual.Addresses[i].ID = common.GenerateUUID()
			individual.Addresses[i].TenantID = individual.TenantID
			if reqContext != nil {
				individual.Addresses[i].CreatedBy = reqContext.UserID
				individual.Addresses[i].ModifiedBy = reqContext.UserID
				individual.Addresses[i].RequestID = reqContext.RequestID
			}
			individual.Addresses[i].CreatedTime = now
			individual.Addresses[i].ModifiedTime = now
		} else {
			if reqContext != nil {
				individual.Addresses[i].ModifiedBy = reqContext.UserID
				individual.Addresses[i].RequestID = reqContext.RequestID
			}
			individual.Addresses[i].ModifiedTime = now
		}
	}

	// Enrich identifiers (present in the request => active under PUT full-replace)
	for i := range individual.Identifiers {
		individual.Identifiers[i].Active = true
		if individual.Identifiers[i].ID == "" {
			individual.Identifiers[i].ID = common.GenerateUUID()
			if reqContext != nil {
				individual.Identifiers[i].CreatedBy = reqContext.UserID
				individual.Identifiers[i].ModifiedBy = reqContext.UserID
				individual.Identifiers[i].RequestID = reqContext.RequestID
			}
			individual.Identifiers[i].CreatedTime = now
			individual.Identifiers[i].ModifiedTime = now
		} else {
			if reqContext != nil {
				individual.Identifiers[i].ModifiedBy = reqContext.UserID
				individual.Identifiers[i].RequestID = reqContext.RequestID
			}
			individual.Identifiers[i].ModifiedTime = now
		}
	}

	// Enrich documents (present in the request => active under PUT full-replace)
	for i := range individual.Documents {
		individual.Documents[i].Active = true
		individual.Documents[i].IndividualID = individual.ID
		if individual.Documents[i].ID == "" {
			individual.Documents[i].ID = common.GenerateUUID()
			if reqContext != nil {
				individual.Documents[i].CreatedBy = reqContext.UserID
				individual.Documents[i].ModifiedBy = reqContext.UserID
				individual.Documents[i].RequestID = reqContext.RequestID
			}
			individual.Documents[i].CreatedTime = now
			individual.Documents[i].ModifiedTime = now
		} else {
			if reqContext != nil {
				individual.Documents[i].ModifiedBy = reqContext.UserID
				individual.Documents[i].RequestID = reqContext.RequestID
			}
			individual.Documents[i].ModifiedTime = now
		}
	}

	return nil
}

// EnrichForDelete enriches individual for delete operation
func (s *enrichmentService) EnrichForDelete(ctx context.Context, individual *models.Individual, reqContext *models.RequestContext) error {
	now := common.GetCurrentTimestamp()

	// Deactivate individual
	individual.Active = false

	// Update audit fields (using clientID from header)
	if reqContext != nil {
		individual.ModifiedBy = reqContext.UserID
	}
	individual.ModifiedTime = now

	// Deactivate identifiers
	for i := range individual.Identifiers {
		individual.Identifiers[i].Active = false
		individual.Identifiers[i].ModifiedTime = now
		if reqContext != nil {
			individual.Identifiers[i].ModifiedBy = reqContext.UserID
		}
	}

	return nil
}
