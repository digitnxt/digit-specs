package models

import (
	"time"

	"gorm.io/gorm"
)

// Address is the DB-layer entity for a physical address attached to an
// individual. The HTTP boundary uses AddressDTO; mappers live in
// address_dto.go.
type Address struct {
	ID               string   `gorm:"column:id;primaryKey"`
	IndividualID     string   `gorm:"column:individualid;not null;index"`
	TenantID         string   `gorm:"column:tenantid"`
	Type             string   `gorm:"column:type"`
	DoorNo           string   `gorm:"column:doorno"`
	BuildingName     string   `gorm:"column:buildingname"`
	Street           string   `gorm:"column:street"`
	Landmark         string   `gorm:"column:landmark"`
	AddressLine1     string   `gorm:"column:addressline1"`
	AddressLine2     string   `gorm:"column:addressline2"`
	City             string   `gorm:"column:city"`
	Region           string   `gorm:"column:region"`
	Country          string   `gorm:"column:country"`
	Pincode          string   `gorm:"column:pincode"`
	BoundaryCode     string   `gorm:"column:localitycode"`
	Latitude         *float64 `gorm:"column:latitude"`
	Longitude        *float64 `gorm:"column:longitude"`
	LocationAccuracy *float64 `gorm:"column:locationaccuracy"`

	Active bool `gorm:"column:active;default:true"`

	CreatedBy    string `gorm:"column:createdBy"`
	ModifiedBy   string `gorm:"column:modifiedBy"`
	CreatedTime  int64  `gorm:"column:createdTime"`
	ModifiedTime int64  `gorm:"column:modifiedTime"`
	RequestID    string `gorm:"column:requestid"`
}

func (Address) TableName() string { return "individual_address_v3" }

func (a *Address) BeforeCreate(tx *gorm.DB) error {
	now := time.Now().UnixMilli()
	if a.CreatedTime == 0 {
		a.CreatedTime = now
	}
	if a.ModifiedTime == 0 {
		a.ModifiedTime = now
	}
	return nil
}

func (a *Address) BeforeUpdate(tx *gorm.DB) error {
	a.ModifiedTime = time.Now().UnixMilli()
	return nil
}
