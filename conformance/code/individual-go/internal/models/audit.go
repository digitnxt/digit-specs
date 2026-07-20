package models

// AuditDetail is the wire-layer representation of audit information,
// nested under `auditDetail` in v3 responses. readOnly per spec — set
// only by the entity → DTO mapper; ignored on inbound requests.
type AuditDetail struct {
	CreatedBy    string `json:"createdBy,omitempty"`
	CreatedTime  int64  `json:"createdTime,omitempty"`
	ModifiedBy   string `json:"modifiedBy,omitempty"`
	ModifiedTime int64  `json:"modifiedTime,omitempty"`
}

// newAuditDetail returns a populated *AuditDetail, or nil if every flat
// audit value is zero (keeps the JSON output omitempty-friendly).
func newAuditDetail(createdBy, modifiedBy string, createdTime, modifiedTime int64) *AuditDetail {
	if createdBy == "" && modifiedBy == "" && createdTime == 0 && modifiedTime == 0 {
		return nil
	}
	return &AuditDetail{
		CreatedBy:    createdBy,
		ModifiedBy:   modifiedBy,
		CreatedTime:  createdTime,
		ModifiedTime: modifiedTime,
	}
}
