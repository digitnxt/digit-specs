package models

// EmployeePatch carries an employee update — the same shape serves the PATCH
// request body and the persistence-layer change set. Nil pointer fields are
// skipped by GORM's Updates(struct); a pointer to a zero value (e.g. *false,
// *"") is written as-is.
//
// Only fields on this struct are updatable. Immutable attributes — id, code,
// userId, individualId, dateOfAppointment, tenantId, jurisdictions, and
// audit columns — are intentionally absent and cannot be rewritten through
// the update path.
//
// Audit fields are hidden from JSON so clients cannot spoof them; they are
// set by the service on every update.
type EmployeePatch struct {
	Status       *string `json:"status,omitempty"`
	EmployeeType *string `json:"employeeType,omitempty"`
	Department   *string `json:"department,omitempty"`
	Designation  *string `json:"designation,omitempty"`
	IsActive     *bool   `json:"isActive,omitempty"`

	// Version is the bumped optimistic-concurrency value (expected+1) written on
	// every patch. It is a plain int (not a pointer) so GORM's Updates(struct)
	// always writes it — the CAS predicate (WHERE version = expected) is applied
	// by the repository. json:"-" so clients can't spoof it via the patch body;
	// the client's expected version travels on PatchEmployeeRequest.Version.
	Version int `json:"-" gorm:"column:version"`

	// See Employee entity — plain form, GORM auto-quotes case-sensitive
	// identifiers. The escape form (column:\"modifiedBy\") only matters for
	// SELECT mapping, but kept consistent here for clarity.
	ModifiedBy   string `json:"-" gorm:"column:modifiedBy"`
	ModifiedTime int64  `json:"-" gorm:"column:modifiedTime"`
}
