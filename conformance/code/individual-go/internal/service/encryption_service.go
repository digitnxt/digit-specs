package service

import (
	"context"

	"individual/internal/clients"
	"individual/internal/common"
	"individual/internal/config"
	"individual/internal/models"

	"github.com/rs/zerolog/log"
)

// EncryptionService handles PII encryption/decryption
type EncryptionService interface {
	EncryptIndividual(ctx context.Context, individual *models.Individual) error
	DecryptIndividual(ctx context.Context, individual *models.Individual) error
	EncryptIndividuals(ctx context.Context, individuals []models.Individual) error
	DecryptIndividuals(ctx context.Context, individuals []models.Individual) error
	HashMobileNumber(mobileNumber string) (string, error)
}

type encryptionService struct {
	vaultClient clients.VaultClient
	config      *config.VaultConfig
	hmacSecret  []byte
}

// NewEncryptionService creates a new encryption service. hmacSecret is the pepper for the
// mobile-number blind index (HMAC-SHA256); it must match the secret the validator uses so a
// stored hash and a lookup hash agree.
func NewEncryptionService(vaultClient clients.VaultClient, config *config.VaultConfig, hmacSecret []byte) EncryptionService {
	return &encryptionService{
		vaultClient: vaultClient,
		config:      config,
		hmacSecret:  hmacSecret,
	}
}

// EncryptIndividual encrypts PII fields in individual
func (s *encryptionService) EncryptIndividual(ctx context.Context, individual *models.Individual) error {
	if !s.config.Enabled {
		log.Debug().Str("individualID", individual.ID).Msg("vault disabled — skipping encryption (mobile hash still computed)")
		// Still hash mobile number for search even without encryption
		if individual.MobileNumber != "" {
			hash, err := s.HashMobileNumber(individual.MobileNumber)
			if err != nil {
				return err
			}
			individual.HashedMobileNumber = hash
		}
		return nil
	}

	// Encrypt mobile number
	if individual.MobileNumber != "" {
		plaintext := individual.MobileNumber
		hash, err := s.HashMobileNumber(plaintext)
		if err != nil {
			return err
		}

		encrypted, err := s.vaultClient.Encrypt(ctx, plaintext, individual.TenantID)
		if err != nil {
			log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Str("field", "mobileNumber").Msg("vault encrypt failed")
			return common.ErrEncryption.WithContext(map[string]interface{}{
				"field": "mobileNumber",
				"error": err.Error(),
			})
		}

		individual.MobileNumber = encrypted
		individual.HashedMobileNumber = hash
	}

	// Encrypt alt contact number
	if individual.AltContactNumber != "" {
		encrypted, err := s.vaultClient.Encrypt(ctx, individual.AltContactNumber, individual.TenantID)
		if err != nil {
			log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Str("field", "altContactNumber").Msg("vault encrypt failed")
			return common.ErrEncryption
		}
		individual.AltContactNumber = encrypted
	}

	// Encrypt identifiers (especially Aadhaar)
	for i := range individual.Identifiers {
		if individual.Identifiers[i].IdentifierType == common.IdentifierTypeAadhaar && individual.Identifiers[i].IdentifierID != "" {
			encrypted, err := s.vaultClient.Encrypt(ctx, individual.Identifiers[i].IdentifierID, individual.TenantID)
			if err != nil {
				log.Error().Err(err).Ctx(ctx).
					Str("individualID", individual.ID).
					Str("identifierType", individual.Identifiers[i].IdentifierType).
					Msg("vault encrypt failed on identifier")
				return common.ErrEncryption.WithContext(map[string]interface{}{
					"field": "identifierId",
					"type":  individual.Identifiers[i].IdentifierType,
				})
			}
			individual.Identifiers[i].IdentifierID = encrypted
		}
	}

	return nil
}

// DecryptIndividual decrypts PII fields in individual
func (s *encryptionService) DecryptIndividual(ctx context.Context, individual *models.Individual) error {
	if !s.config.Enabled {
		log.Debug().Str("individualID", individual.ID).Msg("vault disabled — skipping decryption")
		return nil
	}

	if individual.MobileNumber != "" {
		decrypted, err := s.vaultClient.Decrypt(ctx, individual.MobileNumber, individual.TenantID)
		if err != nil {
			log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Str("field", "mobileNumber").Msg("vault decrypt failed")
			return common.ErrDecryption.WithContext(map[string]interface{}{
				"field": "mobileNumber",
			})
		}
		individual.MobileNumber = decrypted
	}

	if individual.AltContactNumber != "" {
		decrypted, err := s.vaultClient.Decrypt(ctx, individual.AltContactNumber, individual.TenantID)
		if err != nil {
			log.Error().Err(err).Ctx(ctx).Str("individualID", individual.ID).Str("field", "altContactNumber").Msg("vault decrypt failed")
			return common.ErrDecryption
		}
		individual.AltContactNumber = decrypted
	}

	for i := range individual.Identifiers {
		if individual.Identifiers[i].IdentifierType == common.IdentifierTypeAadhaar && individual.Identifiers[i].IdentifierID != "" {
			decrypted, err := s.vaultClient.Decrypt(ctx, individual.Identifiers[i].IdentifierID, individual.TenantID)
			if err != nil {
				log.Error().Err(err).Ctx(ctx).
					Str("individualID", individual.ID).
					Str("identifierType", individual.Identifiers[i].IdentifierType).
					Msg("vault decrypt failed on identifier")
				return common.ErrDecryption.WithContext(map[string]interface{}{
					"field": "identifierId",
					"type":  individual.Identifiers[i].IdentifierType,
				})
			}
			individual.Identifiers[i].IdentifierID = decrypted
		}
	}

	return nil
}

// EncryptIndividuals encrypts multiple individuals
func (s *encryptionService) EncryptIndividuals(ctx context.Context, individuals []models.Individual) error {
	for i := range individuals {
		if err := s.EncryptIndividual(ctx, &individuals[i]); err != nil {
			return err
		}
	}
	return nil
}

// DecryptIndividuals decrypts multiple individuals
func (s *encryptionService) DecryptIndividuals(ctx context.Context, individuals []models.Individual) error {
	for i := range individuals {
		if err := s.DecryptIndividual(ctx, &individuals[i]); err != nil {
			return err
		}
	}
	return nil
}

// HashMobileNumber computes the mobile-number blind index for search indexing.
func (s *encryptionService) HashMobileNumber(mobileNumber string) (string, error) {
	return common.HashMobileNumber(s.hmacSecret, mobileNumber), nil
}
