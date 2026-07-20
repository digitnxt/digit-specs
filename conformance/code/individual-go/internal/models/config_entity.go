package models

import "gorm.io/datatypes"

// Config is the DB-layer entity for per-tenant validation configuration.
// One row per tenant. Wire shape lives in config_dto.go.
type Config struct {
	ID                 int64          `gorm:"column:id;primaryKey;autoIncrement"`
	TenantID           string         `gorm:"column:tenantid;uniqueIndex;not null"`
	MobileRegex        string         `gorm:"column:mobileregex"`
	NameRegex          string         `gorm:"column:nameregex"`
	UniquenessCriteria datatypes.JSON `gorm:"column:uniquenesscriteria;type:jsonb"`
	Version            int            `gorm:"column:version;default:1"`

	CreatedBy    string `gorm:"column:createdBy"`
	ModifiedBy   string `gorm:"column:modifiedBy"`
	CreatedTime  int64  `gorm:"column:createdTime"`
	ModifiedTime int64  `gorm:"column:modifiedTime"`
	RequestID    string `gorm:"column:requestid"`
}

func (Config) TableName() string { return "individual_config_v3" }
