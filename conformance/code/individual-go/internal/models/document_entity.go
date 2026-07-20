package models

import (
	"time"

	"gorm.io/gorm"
)

// Document is the DB-layer entity for an individual-level document
// (proof of residence, etc.). Wire shape lives in document_dto.go.
type Document struct {
	ID           string `gorm:"column:id;primaryKey"`
	IndividualID string `gorm:"column:individualid;not null;index"`
	DocumentType string `gorm:"column:documenttype;not null"`
	FileStoreID  string `gorm:"column:filestoreid;not null"`
	DocumentUID  string `gorm:"column:documentuid"`

	Active bool `gorm:"column:active;default:true"`

	CreatedBy    string `gorm:"column:createdBy"`
	ModifiedBy   string `gorm:"column:modifiedBy"`
	CreatedTime  int64  `gorm:"column:createdTime"`
	ModifiedTime int64  `gorm:"column:modifiedTime"`
	RequestID    string `gorm:"column:requestid"`
}

func (Document) TableName() string { return "individual_document_v3" }

func (d *Document) BeforeCreate(tx *gorm.DB) error {
	now := time.Now().UnixMilli()
	if d.CreatedTime == 0 {
		d.CreatedTime = now
	}
	if d.ModifiedTime == 0 {
		d.ModifiedTime = now
	}
	return nil
}

func (d *Document) BeforeUpdate(tx *gorm.DB) error {
	d.ModifiedTime = time.Now().UnixMilli()
	return nil
}
