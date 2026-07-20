package validator

import (
	"context"

	"individual/internal/common"
	"individual/internal/models"
	"individual/internal/repository"
)

// Validator is the entry-point contract for request validation. Each method
// verifies that the client sent a proper request; per-entity rules live in
// individual.go / address.go / identifier.go / document.go / config.go.
//
// Search-side validation is enforced declaratively by IndividualSearchFilter
// and IndividualExistsFilter binding tags — no Validator method is required.
type Validator interface {
	ValidateCreate(ctx context.Context, individual *models.Individual) error
	ValidateUpdate(ctx context.Context, individual *models.Individual) error
	ValidateDelete(ctx context.Context, individual *models.Individual) error
	ValidateConfig(cfg *models.Config) error
}

type individualValidator struct {
	repo       repository.IndividualRepository
	cfgRepo    repository.ConfigRepository
	hmacSecret []byte
}

// NewValidator builds the validator. The repo dependencies power the
// DB-touching business rules (mobile uniqueness, tenant-config uniqueness).
// hmacSecret is the pepper for the mobile blind-index lookup; it must match the
// secret the encryption service used to store the hash.
func NewValidator(repo repository.IndividualRepository, cfgRepo repository.ConfigRepository, hmacSecret []byte) Validator {
	return &individualValidator{
		repo:       repo,
		cfgRepo:    cfgRepo,
		hmacSecret: hmacSecret,
	}
}

// ValidateCreate runs required + format + business rule checks.
func (v *individualValidator) ValidateCreate(ctx context.Context, individual *models.Individual) error {
	if err := v.validateRequiredFields(individual); err != nil {
		return err
	}
	cfg := v.tenantConfig(ctx, individual.TenantID)
	if err := v.validateFormats(individual, cfg); err != nil {
		return err
	}
	return v.validateBusinessRules(ctx, individual, cfg, true)
}

// ValidateUpdate runs identity + required-field + version + format + business rule checks.
// PUT semantics: the full representation must be sent; required fields are re-validated.
func (v *individualValidator) ValidateUpdate(ctx context.Context, individual *models.Individual) error {
	if individual.ID == "" {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "id",
			"message": "id is required for update",
		})
	}

	// version is required on update: individual is an optimistic-concurrency API,
	// so every PUT must carry the version it is based on (valid versions are >= 1).
	if individual.RowVersion <= 0 {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "version",
			"message": "version is required for update",
		})
	}

	if err := v.validateRequiredFields(individual); err != nil {
		return err
	}

	existing, err := v.repo.FindByID(ctx, individual.ID, individual.TenantID)
	if err != nil || existing == nil {
		return common.ErrNonExistentEntity.WithParams(map[string]interface{}{
			"entity": "Individual",
			"id":     individual.ID,
		})
	}

	// Optimistic-concurrency fast-fail: version is required (checked above) and
	// must match the current row.
	if existing.RowVersion != individual.RowVersion {
		return common.ErrRowVersionMismatch.WithParams(map[string]interface{}{
			"expected": existing.RowVersion,
			"provided": individual.RowVersion,
		})
	}

	cfg := v.tenantConfig(ctx, individual.TenantID)
	if err := v.validateFormats(individual, cfg); err != nil {
		return err
	}
	return v.validateBusinessRules(ctx, individual, cfg, false)
}

// ValidateDelete checks the row exists and is currently active.
func (v *individualValidator) ValidateDelete(ctx context.Context, individual *models.Individual) error {
	if individual.ID == "" {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "id",
			"message": "id is required for delete",
		})
	}

	existing, err := v.repo.FindByID(ctx, individual.ID, individual.TenantID)
	if err != nil || existing == nil || !existing.Active {
		return common.ErrNonExistentEntity.WithParams(map[string]interface{}{
			"entity": "Individual",
			"id":     individual.ID,
		})
	}
	return nil
}
