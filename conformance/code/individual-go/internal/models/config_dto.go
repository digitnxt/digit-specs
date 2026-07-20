package models

import (
	"encoding/json"

	"gorm.io/datatypes"
)

// ConfigDTO is the wire shape per v3 spec. tenantId is omitted from the
// wire (sourced from the X-Tenant-ID header on the request).
type ConfigDTO struct {
	MobileRegex        string   `json:"mobileRegex,omitempty"`
	NameRegex          string   `json:"nameRegex,omitempty"`
	UniquenessCriteria []string `json:"uniquenessCriteria,omitempty"`
	Version            int      `json:"version,omitempty"`
	RequestID          string   `json:"requestId,omitempty"`

	AuditDetail *AuditDetail `json:"auditDetail,omitempty"`
}

func ConfigFromEntity(e *Config) *ConfigDTO {
	if e == nil {
		return nil
	}
	d := &ConfigDTO{
		MobileRegex: e.MobileRegex,
		NameRegex:   e.NameRegex,
		Version:     e.Version,
		RequestID:   e.RequestID,
		AuditDetail: newAuditDetail(e.CreatedBy, e.ModifiedBy, e.CreatedTime, e.ModifiedTime),
	}
	if len(e.UniquenessCriteria) > 0 {
		// Best-effort decode; on malformed JSON leave the slice nil so the
		// caller surfaces an empty array instead of crashing.
		_ = json.Unmarshal(e.UniquenessCriteria, &d.UniquenessCriteria)
	}
	return d
}

// ConfigToEntity converts a wire DTO into a DB entity.
// AuditDetail is readOnly per spec — the service/repo set audit fields.
func ConfigToEntity(d *ConfigDTO) *Config {
	if d == nil {
		return nil
	}
	e := &Config{
		MobileRegex: d.MobileRegex,
		NameRegex:   d.NameRegex,
	}
	if len(d.UniquenessCriteria) > 0 {
		if b, err := json.Marshal(d.UniquenessCriteria); err == nil {
			e.UniquenessCriteria = datatypes.JSON(b)
		}
	}
	return e
}
