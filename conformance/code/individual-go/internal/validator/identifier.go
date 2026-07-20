package validator

import (
	"fmt"

	"individual/internal/common"
	"individual/internal/models"
)

// validateIdentifiers — per the v3 spec:
//   - identifierType: required, enum
//     [NATIONAL_ID, AADHAAR, PASSPORT, VOTER_ID, PAN, DRIVING_LICENSE, SYSTEM_GENERATED]
//   - identifierId:   required, ≤64
//   - documentType:   ≤64 when supplied
//   - fileStoreId:    ≤64 when supplied
//   - identifierType is unique within identifiers[]
func (v *individualValidator) validateIdentifiers(identifiers []models.Identifier) error {
	seen := make(map[string]bool, len(identifiers))
	for i, id := range identifiers {
		prefix := fmt.Sprintf("identifiers[%d]", i)

		if id.IdentifierType == "" {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".identifierType",
				"message": "identifierType is required",
			})
		}
		if !isValidIdentifierType(id.IdentifierType) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".identifierType",
				"value":   id.IdentifierType,
				"message": "identifierType must be one of NATIONAL_ID, AADHAAR, PASSPORT, VOTER_ID, PAN, DRIVING_LICENSE, SYSTEM_GENERATED",
			})
		}
		if seen[id.IdentifierType] {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "identifiers",
				"message": "duplicate identifierType: " + id.IdentifierType,
			})
		}
		seen[id.IdentifierType] = true

		if id.IdentifierID == "" {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".identifierId",
				"message": "identifierId is required",
			})
		}
		if err := maxLen(prefix+".identifierId", id.IdentifierID, identifierIDMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".documentType", id.DocumentType, identifierDocTypeMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".fileStoreId", id.FileStoreID, identifierFileStoreMaxLen); err != nil {
			return err
		}
	}
	return nil
}

// isValidIdentifierType returns true for spec-defined Identifier.identifierType values (see validIdentifierTypes).
func isValidIdentifierType(t string) bool {
	return validIdentifierTypes[t]
}
