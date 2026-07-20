package models

import "time"

// Employee is the persistence-layer entity for the employee_v3 table.
// It carries gorm tags only and is never serialized at the API boundary —
// use EmployeeResponse (DTO) for that.
type Employee struct {
	ID                string `gorm:"primaryKey;default:uuid_generate_v4()"`
	Code              string
	UserID            string
	IndividualID      string
	Status            string
	EmployeeType      string
	DateOfAppointment *time.Time
	Department        string
	Designation       string
	IsActive          bool            `gorm:"default:true"`
	Jurisdictions     []*Jurisdiction `gorm:"foreignKey:EmployeeID"`
	TenantID          string

	// Version is the optimistic-concurrency token. Set to 1 on create and
	// bumped on every mutation; updates compare-and-swap on it (see repository
	// Update). Column named `version` — same word end-to-end.
	Version int `gorm:"column:version"`

	// Column tags use plain camelCase. GORM's postgres dialector auto-quotes
	// identifiers in generated SQL (so case is preserved as `"createdBy"`),
	// which matches the columns created by V20260427120000__standardize_audit_fields.
	// The previous form with embedded escapes (column:\"createdBy\") wrote
	// correctly but stored the field's DBName with literal quotes, so
	// result-row → struct mapping never matched and the fields came back
	// as zero values on SELECT.
	CreatedBy    string `gorm:"column:createdBy"`
	ModifiedBy   string `gorm:"column:modifiedBy"`
	CreatedTime  int64  `gorm:"column:createdTime"`
	ModifiedTime int64  `gorm:"column:modifiedTime"`
}

// TableName specifies the table name for the Employee entity.
func (Employee) TableName() string {
	return "employee_v3"
}

// ToEntity converts a strict-PUT UpdateEmployeeRequest into a fully populated
// Employee ready for full-row overwrite. Every mutable field is required at
// bind time, so the assignment is unconditional — no nil-checks needed.
//
// Immutable fields (id, code, userId, individualId, dateOfAppointment,
// tenantId, createdBy, createdTime) are carried forward from `existing`.
// Audit fields (modifiedBy, modifiedTime) are server-set.
//
// Jurisdictions are handled separately by the service (reconciled against the
// supplied array); they are not part of the employee row itself.
func (r *UpdateEmployeeRequest) ToEntity(userID string, existing Employee) Employee {
	e := existing
	e.EmployeeType = r.EmployeeType
	e.Department = r.Department
	e.Designation = r.Designation
	e.Status = r.Status
	e.IsActive = *r.IsActive
	e.ModifiedBy = userID
	e.ModifiedTime = time.Now().UnixMilli()
	return e
}
