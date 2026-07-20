package validator

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"individual/internal/common"
	"individual/internal/models"
)

// validateRequiredFields enforces the spec's required fields on create/update.
//   - givenName: required
//   - tenantId:  required (sourced from X-Tenant-ID header)
//   - gender:    required
func (v *individualValidator) validateRequiredFields(individual *models.Individual) error {
	if individual.GivenName == "" {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "givenName",
			"message": "givenName is required",
		})
	}
	if individual.TenantID == "" {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "tenantId",
			"message": "tenantId is required (from X-Tenant-ID header)",
		})
	}
	if strings.TrimSpace(individual.Gender) == "" {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "gender",
			"message": "gender is required",
		})
	}
	return nil
}

// validateFormats applies per-field format + length checks per the v3 spec.
//   - email:            ≤254, RFC-ish
//   - gender:           enum MALE | FEMALE | OTHER
//   - givenName:        ≤128; tenant nameRegex if configured, else alphabets/spaces baseline
//   - familyName:       ≤128; tenant nameRegex if configured, else alphabets/spaces baseline
//   - otherNames:       ≤256
//   - mobileNumber:     ≤20;  tenant mobileRegex if configured, else 6–15 digit baseline
//   - altContactNumber: ≤20
//   - locale:           ≤16
//   - fatherName:       ≤128
//   - husbandName:      ≤128
//   - photo:            ≤512
//   - userId:           ≤64
//   - age:              0–150
//   - additionalAttributes: ≤50 entries; key matches ^[a-zA-Z0-9_.-]+$, key ≤128, value ≤1024
func (v *individualValidator) validateFormats(individual *models.Individual, cfg *models.Config) error {
	// Per-field pattern source: a configured tenant regex overrides the baseline.
	mobileRegex, nameRegex := "", ""
	if cfg != nil {
		mobileRegex = cfg.MobileRegex
		nameRegex = cfg.NameRegex
	}

	if individual.Email != "" {
		if err := maxLen("email", individual.Email, emailMaxLen); err != nil {
			return err
		}
		if !emailRegex.MatchString(individual.Email) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "email",
				"value":   individual.Email,
				"message": "email must be a valid email address, e.g. name@example.com",
			})
		}
	}

	if individual.Gender != "" && !isValidGender(individual.Gender) {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "gender",
			"value":   individual.Gender,
			"message": "gender must be MALE, FEMALE, or OTHER",
		})
	}

	if individual.GivenName != "" {
		if err := maxLen("givenName", individual.GivenName, nameMaxLen); err != nil {
			return err
		}
		if err := checkPattern("givenName", individual.GivenName, nameRegex, alphaOnly,
			"givenName must contain only alphabets and spaces"); err != nil {
			return err
		}
	}

	if individual.FamilyName != "" {
		if err := maxLen("familyName", individual.FamilyName, nameMaxLen); err != nil {
			return err
		}
		if err := checkPattern("familyName", individual.FamilyName, nameRegex, alphaOnly,
			"familyName must contain only alphabets and spaces"); err != nil {
			return err
		}
	}

	if err := maxLen("otherNames", individual.OtherNames, otherNamesMaxLen); err != nil {
		return err
	}
	if err := maxLen("mobileNumber", individual.MobileNumber, mobileMaxLen); err != nil {
		return err
	}
	if err := checkPattern("mobileNumber", individual.MobileNumber, mobileRegex, mobileBaseline,
		"mobileNumber must be 6-15 digits"); err != nil {
		return err
	}
	if err := maxLen("altContactNumber", individual.AltContactNumber, mobileMaxLen); err != nil {
		return err
	}
	if err := maxLen("locale", individual.Locale, localeMaxLen); err != nil {
		return err
	}
	if err := maxLen("fatherName", individual.FatherName, nameMaxLen); err != nil {
		return err
	}
	if err := maxLen("husbandName", individual.HusbandName, nameMaxLen); err != nil {
		return err
	}
	if err := maxLen("photo", individual.Photo, photoMaxLen); err != nil {
		return err
	}
	if err := maxLen("userId", individual.UserID, userIDMaxLen); err != nil {
		return err
	}

	if individual.Age != nil && (*individual.Age < 0 || *individual.Age > maxAgeYears) {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "age",
			"value":   *individual.Age,
			"message": "age must be between 0 and 150",
		})
	}

	// dateOfBirth must not be in the future, and must not be > 150 years in
	// the past (mirrors the age bound).
	if individual.DateOfBirth != nil && !individual.DateOfBirth.IsZero() {
		now := time.Now()
		if individual.DateOfBirth.After(now) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "dateOfBirth",
				"value":   individual.DateOfBirth.Format("2006-01-02"),
				"message": "dateOfBirth must not be in the future",
			})
		}
		if individual.DateOfBirth.Before(now.AddDate(-maxAgeYears, 0, 0)) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "dateOfBirth",
				"value":   individual.DateOfBirth.Format("2006-01-02"),
				"message": "dateOfBirth must not be more than 150 years in the past",
			})
		}
	}

	return validateAdditionalAttributes(individual.AdditionalDetails)
}

func validateAdditionalAttributes(attrs models.JSONB) error {
	if len(attrs) == 0 {
		return nil
	}
	if len(attrs) > maxAdditionalAttributes {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "additionalAttributes",
			"message": "additionalAttributes must contain at most 50 entries",
		})
	}
	for key, val := range attrs {
		if len(key) > attrKeyMaxLen {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "additionalAttributes." + key,
				"message": "additionalAttributes key must not exceed 128 characters",
			})
		}
		if !additionalAttrKeyRegex.MatchString(key) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "additionalAttributes." + key,
				"message": "additionalAttributes keys must match ^[a-zA-Z0-9_.-]+$",
			})
		}
		s, ok := val.(string)
		if !ok {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "additionalAttributes." + key,
				"message": "additionalAttributes values must be strings",
			})
		}
		if len(s) > attrValueMaxLen {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "additionalAttributes." + key,
				"message": "additionalAttributes value must not exceed 1024 characters",
			})
		}
	}
	return nil
}

// validateBusinessRules enforces:
//   - at-least-one of mobileNumber / email
//   - array maxes (address ≤16, identifiers ≤16; documents ≤20 is enforced in
//     validateDocuments)
//   - tenant uniquenessCriteria (mobileNumber / name) — no natural-key
//     uniqueness is enforced unless the tenant opts in via config
//   - nested-entity dispatch (addresses, identifiers, documents)
//
// Format/pattern rules (incl. tenant mobileRegex / nameRegex) live in
// validateFormats.
func (v *individualValidator) validateBusinessRules(ctx context.Context, individual *models.Individual, cfg *models.Config, isCreate bool) error {
	if individual.MobileNumber == "" && individual.Email == "" {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "mobileNumber/email",
			"message": "at least one of mobileNumber or email is required",
		})
	}

	if len(individual.Addresses) > maxAddresses {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "address",
			"message": "address must contain at most 16 entries",
		})
	}
	if len(individual.Identifiers) > maxIdentifiers {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "identifiers",
			"message": "identifiers must contain at most 16 entries",
		})
	}

	if err := v.applyUniquenessCriteria(ctx, individual, cfg, isCreate); err != nil {
		return err
	}
	if len(individual.Identifiers) > 0 {
		if err := v.validateIdentifiers(individual.Identifiers); err != nil {
			return err
		}
	}
	if len(individual.Addresses) > 0 {
		if err := v.validateAddresses(individual.Addresses); err != nil {
			return err
		}
	}
	if len(individual.Documents) > 0 {
		if err := v.validateDocuments(individual.Documents); err != nil {
			return err
		}
	}
	return nil
}

// tenantConfig loads the per-tenant validation config, or nil when none is set
// (or the config repo is unavailable). Fetched once per request and threaded
// into both the format checks and the uniqueness checks.
func (v *individualValidator) tenantConfig(ctx context.Context, tenantID string) *models.Config {
	if v.cfgRepo == nil || tenantID == "" {
		return nil
	}
	cfg, err := v.cfgRepo.GetByTenant(ctx, tenantID)
	if err != nil {
		return nil
	}
	return cfg
}

// mobileDuplicate returns an existing individual sharing this mobile number in
// the tenant, or nil. Validation runs before the service encrypts the mobile,
// so HashedMobileNumber is usually empty here — we compute the hash on the fly
// and try the hash lookup first (covers encrypted-at-rest tenants), then fall
// back to the plaintext lookup for tenants that store plaintext.
func (v *individualValidator) mobileDuplicate(ctx context.Context, individual *models.Individual) *models.Individual {
	if individual.MobileNumber == "" {
		return nil
	}
	if hash := common.HashMobileNumber(v.hmacSecret, individual.MobileNumber); hash != "" {
		if existing, _ := v.repo.FindByMobileHash(ctx, hash, individual.TenantID); existing != nil {
			return existing
		}
	}
	existing, _ := v.repo.FindByMobilePlain(ctx, individual.MobileNumber, individual.TenantID)
	return existing
}

// applyUniquenessCriteria enforces natural-key uniqueness ONLY for the fields a
// tenant opts into via config uniquenessCriteria. With no config, no natural-key
// uniqueness is enforced — id / individualId remain unique via their own keys.
func (v *individualValidator) applyUniquenessCriteria(ctx context.Context, individual *models.Individual, cfg *models.Config, isCreate bool) error {
	if cfg == nil {
		return nil
	}

	var criteria []string
	if cfg.UniquenessCriteria != nil {
		_ = json.Unmarshal(cfg.UniquenessCriteria, &criteria)
	}
	for _, field := range criteria {
		switch strings.ToLower(field) {
		case "mobilenumber":
			if existing := v.mobileDuplicate(ctx, individual); existing != nil && (isCreate || existing.ID != individual.ID) {
				return common.ErrUniqueEntity.WithParams(map[string]interface{}{
					"field":   "mobileNumber",
					"message": "mobileNumber already exists for this tenant",
				})
			}
		case "name":
			if individual.GivenName == "" && individual.FamilyName == "" {
				continue
			}
			if existing, _ := v.repo.FindByName(ctx, individual.GivenName, individual.FamilyName, individual.TenantID); existing != nil && (isCreate || existing.ID != individual.ID) {
				return common.ErrUniqueEntity.WithParams(map[string]interface{}{
					"field":   "name",
					"message": "name already exists for this tenant",
				})
			}
		}
	}
	return nil
}
