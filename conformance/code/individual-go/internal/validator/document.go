package validator

import (
	"fmt"

	"individual/internal/common"
	"individual/internal/models"
)

// validateDocuments — per the v3 spec:
//   - documents:    maxItems 20
//   - documentType: required, 2–64
//   - fileStoreId:  required, 2–64
//   - documentUid:  ≤64 when supplied
func (v *individualValidator) validateDocuments(documents []models.Document) error {
	if len(documents) > maxDocuments {
		return common.ErrValidation.WithParams(map[string]interface{}{
			"field":   "documents",
			"message": "documents must contain at most 20 entries",
		})
	}
	for i, d := range documents {
		prefix := fmt.Sprintf("documents[%d]", i)

		if d.DocumentType == "" {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".documentType",
				"message": "documentType is required",
			})
		}
		if len(d.DocumentType) < documentTypeMinLen || len(d.DocumentType) > documentTypeMaxLen {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".documentType",
				"message": "documentType must be 2-64 characters",
			})
		}
		if d.FileStoreID == "" {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".fileStoreId",
				"message": "fileStoreId is required",
			})
		}
		if len(d.FileStoreID) < fileStoreMinLen || len(d.FileStoreID) > fileStoreMaxLen {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".fileStoreId",
				"message": "fileStoreId must be 2-64 characters",
			})
		}
		if err := maxLen(prefix+".documentUid", d.DocumentUID, documentUIDMaxLen); err != nil {
			return err
		}
	}
	return nil
}
