package models

// AuditDetail is the DTO-side audit envelope returned in API responses.
// The entity counterpart is the flat CreatedBy/ModifiedBy/CreatedTime/ModifiedTime
// fields on the Employee and Jurisdiction entities.
type AuditDetail struct {
	CreatedBy    string `json:"createdBy,omitempty"`
	ModifiedBy   string `json:"modifiedBy,omitempty"`
	CreatedTime  int64  `json:"createdTime,omitempty"`
	ModifiedTime int64  `json:"modifiedTime,omitempty"`
}

type Error struct {
	Code        string   `json:"code"`
	Message     string   `json:"message"`
	Description string   `json:"description,omitempty"`
	Params      []string `json:"params,omitempty"`
}
