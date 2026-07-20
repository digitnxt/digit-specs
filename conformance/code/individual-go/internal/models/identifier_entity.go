package models

import (
	"time"

	"gorm.io/gorm"
)

// Identifier is the DB-layer entity for a government-issued identifier
// attached to an individual (Aadhaar, PAN, system-generated, etc.).
// Wire shape lives in identifier_dto.go.
type Identifier struct {
	ID             string `gorm:"column:id;primaryKey"`
	IndividualID   string `gorm:"column:individualid;not null;index"`
	IdentifierType string `gorm:"column:identifiertype"`
	IdentifierID   string `gorm:"column:identifierid"`
	Verified       bool   `gorm:"column:verified;default:false"`
	DocumentType   string `gorm:"column:documenttype"`
	FileStoreID    string `gorm:"column:filestoreid"`
	Active         bool   `gorm:"column:active;default:true"`

	CreatedBy    string `gorm:"column:createdBy"`
	ModifiedBy   string `gorm:"column:modifiedBy"`
	CreatedTime  int64  `gorm:"column:createdTime"`
	ModifiedTime int64  `gorm:"column:modifiedTime"`
	RequestID    string `gorm:"column:requestid"`
}

func (Identifier) TableName() string { return "individual_identifier_v3" }

func (i *Identifier) BeforeCreate(tx *gorm.DB) error {
	now := time.Now().UnixMilli()
	if i.CreatedTime == 0 {
		i.CreatedTime = now
	}
	if i.ModifiedTime == 0 {
		i.ModifiedTime = now
	}
	return nil
}

func (i *Identifier) BeforeUpdate(tx *gorm.DB) error {
	i.ModifiedTime = time.Now().UnixMilli()
	return nil
}
