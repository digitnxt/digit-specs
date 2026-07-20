package models

import (
	"database/sql/driver"
	"encoding/json"
	"time"

	"gorm.io/gorm"
)

// JSONB is the custom GORM type used for jsonb columns.
type JSONB map[string]interface{}

func (j JSONB) Value() (driver.Value, error) {
	return json.Marshal(j)
}

func (j *JSONB) Scan(value interface{}) error {
	if value == nil {
		*j = make(map[string]interface{})
		return nil
	}
	bytes, ok := value.([]byte)
	if !ok {
		return nil
	}
	return json.Unmarshal(bytes, j)
}

// Individual is the DB-layer entity. GORM operates on this type; the HTTP
// boundary uses IndividualDTO and the mappers in individual_dto.go.
type Individual struct {
	ID                   string     `gorm:"column:id;primaryKey"`
	IndividualID         string     `gorm:"column:individualid;uniqueIndex"`
	TenantID             string     `gorm:"column:tenantid;not null;index"`
	GivenName            string     `gorm:"column:givenname"`
	FamilyName           string     `gorm:"column:familyname"`
	OtherNames           string     `gorm:"column:othernames"`
	DateOfBirth          *time.Time `gorm:"column:dateofbirth"`
	Gender               string     `gorm:"column:gender"`
	Age                  *int       `gorm:"column:age"`
	MobileNumber         string     `gorm:"column:mobilenumber"`
	HashedMobileNumber   string     `gorm:"column:hashedmobilenumber;index"`
	MobileNumberVerified bool       `gorm:"column:mobilenumberverified;default:false"`
	AltContactNumber     string     `gorm:"column:altcontactnumber"`
	Email                string     `gorm:"column:email"`
	EmailVerified        bool       `gorm:"column:emailverified;default:false"`
	Locale               string     `gorm:"column:locale"`
	Active               bool       `gorm:"column:active;default:false"`
	FatherName           string     `gorm:"column:fathername"`
	HusbandName          string     `gorm:"column:husbandname"`
	Photo                string     `gorm:"column:photo"`
	UserID               string     `gorm:"column:userid"`
	AdditionalDetails    JSONB      `gorm:"column:additionaldetails;type:jsonb"`
	CreatedBy            string     `gorm:"column:createdBy"`
	ModifiedBy           string     `gorm:"column:modifiedBy"`
	CreatedTime          int64      `gorm:"column:createdTime"`
	ModifiedTime         int64      `gorm:"column:modifiedTime"`
	RowVersion           int        `gorm:"column:rowversion;default:1"`
	RequestID            string     `gorm:"column:requestid"`

	// Relations — all loaded and written manually by the repository (one-to-many
	// via each child's individualid FK).
	Addresses   []Address    `gorm:"-"`
	Identifiers []Identifier `gorm:"-"`
	Documents   []Document   `gorm:"-"`
}

func (Individual) TableName() string { return "individual_v3" }

func (i *Individual) BeforeCreate(tx *gorm.DB) error {
	now := time.Now().UnixMilli()
	if i.CreatedTime == 0 {
		i.CreatedTime = now
	}
	if i.ModifiedTime == 0 {
		i.ModifiedTime = now
	}
	return nil
}

func (i *Individual) BeforeUpdate(tx *gorm.DB) error {
	i.ModifiedTime = time.Now().UnixMilli()
	return nil
}
