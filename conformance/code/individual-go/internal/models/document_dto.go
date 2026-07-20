package models

// DocumentDTO is the wire shape per v3 spec.
// IndividualID is the FK on the entity but isn't surfaced on the wire —
// documents are returned nested inside an Individual.
type DocumentDTO struct {
	ID           string `json:"id"`
	DocumentType string `json:"documentType,omitempty"`
	FileStoreID  string `json:"fileStoreId,omitempty"`
	DocumentUID  string `json:"documentUid,omitempty"`
	RequestID    string `json:"requestId,omitempty"`

	AuditDetail *AuditDetail `json:"auditDetail,omitempty"`
}

func DocumentFromEntity(e *Document) *DocumentDTO {
	if e == nil {
		return nil
	}
	return &DocumentDTO{
		ID:           e.ID,
		DocumentType: e.DocumentType,
		FileStoreID:  e.FileStoreID,
		DocumentUID:  e.DocumentUID,
		RequestID:    e.RequestID,
		AuditDetail:  newAuditDetail(e.CreatedBy, e.ModifiedBy, e.CreatedTime, e.ModifiedTime),
	}
}

func DocumentToEntity(d *DocumentDTO) *Document {
	if d == nil {
		return nil
	}
	return &Document{
		ID:           d.ID,
		DocumentType: d.DocumentType,
		FileStoreID:  d.FileStoreID,
		DocumentUID:  d.DocumentUID,
		// IndividualID FK and audit fields populated by enrichment.
	}
}
