package models

import "time"

// Jurisdiction is the persistence-layer entity for the employee_jurisdiction_v3 table.
// It carries gorm tags only and is never serialized at the API boundary —
// use JurisdictionResponse (DTO) for that.
type Jurisdiction struct {
	ID               string `gorm:"primaryKey;default:uuid_generate_v4()"`
	EmployeeID       string
	BoundaryRelation []BoundaryRef `gorm:"type:jsonb;serializer:json"`
	IsActive         bool          `gorm:"default:true"`
	TenantID         string

	// Version is the optimistic-concurrency token, independent of the owning
	// employee's version. Set to 1 on create, bumped on every mutation; updates
	// compare-and-swap on it. When a jurisdiction is supplied inside an employee
	// PUT/PATCH body, this field carries the client's last-read version so the
	// reconcile can guard the in-place update (id + version required together).
	Version int `gorm:"column:version"`

	// See Employee.CreatedBy comment — escape-form (column:\"createdBy\") writes
	// correctly but fails result-row → struct mapping. Plain form works.
	CreatedBy    string `gorm:"column:createdBy"`
	ModifiedBy   string `gorm:"column:modifiedBy"`
	CreatedTime  int64  `gorm:"column:createdTime"`
	ModifiedTime int64  `gorm:"column:modifiedTime"`
}

// TableName specifies the table name for the Jurisdiction entity.
func (Jurisdiction) TableName() string {
	return "employee_jurisdiction_v3"
}

// ToEntity converts an UpdateJurisdictionRequest into a fully populated
// Jurisdiction ready for a PUT-style overwrite. Immutable fields (id,
// employeeId, tenantId, createdBy, createdTime) are carried forward from the
// existing row; mutable fields come from the request; audit fields come from
// the server. The caller passes the existing row so this function can keep
// the conversion intentional and explicit — no GORM zero-value magic.
//
// isActive is optional in the request: when omitted it preserves the value
// from `existing` (since jurisdiction has no dedicated /deactivate endpoint).
func (r *UpdateJurisdictionRequest) ToEntity(userID string, existing Jurisdiction) Jurisdiction {
	j := existing
	j.BoundaryRelation = r.BoundaryRelation
	if r.IsActive != nil {
		j.IsActive = *r.IsActive
	}
	j.ModifiedBy = userID
	j.ModifiedTime = time.Now().UnixMilli()
	return j
}
