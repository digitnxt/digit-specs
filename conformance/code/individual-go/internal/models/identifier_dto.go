package models

// IdentifierDTO is the wire shape per v3 spec.
type IdentifierDTO struct {
	ID             string `json:"id"`
	IndividualID   string `json:"individualId,omitempty"`
	IdentifierType string `json:"identifierType,omitempty"`
	IdentifierID   string `json:"identifierId,omitempty"`
	Verified       bool   `json:"verified"`
	DocumentType   string `json:"documentType,omitempty"`
	FileStoreID    string `json:"fileStoreId,omitempty"`
	Active         bool   `json:"active"`
	RequestID      string `json:"requestId,omitempty"`

	AuditDetail *AuditDetail `json:"auditDetail,omitempty"`
}

func IdentifierFromEntity(e *Identifier) *IdentifierDTO {
	if e == nil {
		return nil
	}
	return &IdentifierDTO{
		ID:             e.ID,
		IndividualID:   e.IndividualID,
		IdentifierType: e.IdentifierType,
		IdentifierID:   e.IdentifierID,
		Verified:       e.Verified,
		DocumentType:   e.DocumentType,
		FileStoreID:    e.FileStoreID,
		Active:         e.Active,
		RequestID:      e.RequestID,
		AuditDetail:    newAuditDetail(e.CreatedBy, e.ModifiedBy, e.CreatedTime, e.ModifiedTime),
	}
}

func IdentifierToEntity(d *IdentifierDTO) *Identifier {
	if d == nil {
		return nil
	}
	return &Identifier{
		ID:             d.ID,
		IdentifierType: d.IdentifierType,
		IdentifierID:   d.IdentifierID,
		Verified:       d.Verified,
		DocumentType:   d.DocumentType,
		FileStoreID:    d.FileStoreID,
		Active:         d.Active,
		// IndividualID FK is set by the enrichment service from the parent.
		// Audit fields are set by enrichment.
	}
}
