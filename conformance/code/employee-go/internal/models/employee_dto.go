package models

import "time"

// CreateEmployeeRequest represents the request payload for creating an employee.
//
// employeeType, department, and designation are mandatory — an employee must
// have a defined role to be persisted. code is optional (auto-generated when
// absent). userId and individualId are optional cross-service references that
// can be linked later via staged onboarding.
type CreateEmployeeRequest struct {
	// Binding max values are kept in lock-step with the employee_v3 column
	// widths so over-length input is rejected in-process (clean 400) instead of
	// overflowing a column and surfacing as a Postgres 22001 -> 500. code,
	// userId, individualId: VARCHAR(64); status: VARCHAR(64); employeeType,
	// department, designation: VARCHAR(128) (see the widen-columns migration).
	// userId/individualId use 64 to match the identifier width the individual
	// service uses platform-wide.
	Code              string          `json:"code,omitempty" binding:"omitempty,min=1,max=64"`
	UserID            string          `json:"userId,omitempty" binding:"omitempty,min=1,max=64"`
	IndividualID      string          `json:"individualId,omitempty" binding:"omitempty,min=1,max=64"`
	Status            string          `json:"status,omitempty" binding:"omitempty,min=1,max=64"`
	EmployeeType      string          `json:"employeeType" binding:"required,min=1,max=128"`
	DateOfAppointment *time.Time      `json:"dateOfAppointment,omitempty"`
	Department        string          `json:"department" binding:"required,min=1,max=128"`
	Designation       string          `json:"designation" binding:"required,min=1,max=128"`
	IsActive          *bool           `json:"isActive,omitempty"`
	Jurisdictions     []*Jurisdiction `json:"jurisdictions,omitempty"`
}

// UpdateEmployeeRequest is the PUT body — a strict full-state declaration.
// Every mutable field is required; the client must describe the complete
// post-update state of the employee. This is what makes PUT meaningfully
// different from PATCH on the same resource.
//
// Field rules:
//   - employeeType, department, designation, status: required non-empty
//     strings. min=1 prevents the client from supplying "" to bypass intent.
//   - isActive: *bool + required. Pointer so the JSON parser can tell false
//     from "omitted"; required so omission returns 400 — no accidental flip.
//   - jurisdictions: required slice. Supply [] to deactivate the whole
//     collection; supply [...] to reconcile it (item with id+version → update
//     in place, id-less → insert, omitted existing → deactivate).
//
// Immutable fields (id, code, userId, individualId, dateOfAppointment,
// tenantId, createdBy, createdTime) are intentionally absent. Clients that
// include them in the body have no effect — the binder ignores them and the
// repo's Omit() refuses to write them at the SQL layer.
//
// If the client only wants to change a subset of fields, they should use
// PATCH /employees/{id} instead. PUT exists for callers who hold the full
// desired state (admin tools, declarative provisioners, GET-then-mutate-PUT
// workflows).
type UpdateEmployeeRequest struct {
	EmployeeType  string          `json:"employeeType"  binding:"required,min=1,max=128"`
	Department    string          `json:"department"    binding:"required,min=1,max=128"`
	Designation   string          `json:"designation"   binding:"required,min=1,max=128"`
	Status        string          `json:"status"        binding:"required,min=1,max=64"`
	IsActive      *bool           `json:"isActive"      binding:"required"`
	Jurisdictions []*Jurisdiction `json:"jurisdictions" binding:"required"`
	// Version is the optimistic-concurrency token the client last read. Required
	// (min=1 rejects 0); the update is a compare-and-swap on it → 409
	// ROW_VERSION_MISMATCH on staleness. Jurisdiction items carry their own
	// id+version for in-place reconcile (see reconcileJurisdictions).
	Version int `json:"version" binding:"required,min=1"`
}

// PatchEmployeeRequest is the PATCH body — partial update of an existing
// employee. Every field is a pointer (or pointer-to-slice) so the binding
// layer can distinguish "omitted" from "explicit zero value." Omitted fields
// preserve the existing DB value; supplied fields overwrite.
//
// Mutable surface (per the entity): status, employeeType, department,
// designation, isActive, jurisdictions. Immutable fields (code, userId,
// individualId, dateOfAppointment) are intentionally absent here and cannot
// be changed via PATCH.
//
// jurisdictions has reconcile-on-set semantics: when supplied, the collection
// is reconciled against the array (id+version → update in place, id-less →
// insert, omitted existing → deactivate). An empty array deactivates all
// jurisdictions; nil (omitted) leaves them untouched.
//
// At least one field must be supplied — HasAnyField returns false for an
// empty body, which the service rejects with 400.
type PatchEmployeeRequest struct {
	Status        *string          `json:"status,omitempty"        binding:"omitempty,min=1,max=64"`
	EmployeeType  *string          `json:"employeeType,omitempty"  binding:"omitempty,min=1,max=128"`
	Department    *string          `json:"department,omitempty"    binding:"omitempty,min=1,max=128"`
	Designation   *string          `json:"designation,omitempty"   binding:"omitempty,min=1,max=128"`
	IsActive      *bool            `json:"isActive,omitempty"`
	Jurisdictions *[]*Jurisdiction `json:"jurisdictions,omitempty"`
	// Version is the optimistic-concurrency token, required even on a partial
	// update — the client must prove it saw the current employee state. Not
	// counted by HasAnyField (it is the guard, not a mutable field).
	Version int `json:"version" binding:"required,min=1"`
}

// HasAnyField reports whether the request supplies at least one mutable field.
// An empty body (`{}`) returns false, which callers translate to a 400.
func (r *PatchEmployeeRequest) HasAnyField() bool {
	return r.Status != nil || r.EmployeeType != nil || r.Department != nil ||
		r.Designation != nil || r.IsActive != nil || r.Jurisdictions != nil
}

// EmployeeResponse represents the response payload for employee operations
type EmployeeResponse struct {
	ID                string                  `json:"id"`
	Code              string                  `json:"code,omitempty"`
	UserID            string                  `json:"userId,omitempty"`
	IndividualID      string                  `json:"individualId,omitempty"`
	Status            string                  `json:"status,omitempty"`
	EmployeeType      string                  `json:"employeeType,omitempty"`
	DateOfAppointment *time.Time              `json:"dateOfAppointment,omitempty"`
	Department        string                  `json:"department,omitempty"`
	Designation       string                  `json:"designation,omitempty"`
	IsActive          bool                    `json:"isActive"`
	Version           int                     `json:"version"`
	Jurisdictions     []*JurisdictionResponse `json:"jurisdictions,omitempty"`
	AuditDetail       AuditDetail             `json:"auditDetail"`
}

// EmployeeSearchCriteria carries client-supplied search filters for employees.
// Sort order is fixed server-side (createdTime DESC); tenant scoping is
// resolved from ctx in the service/repo layer, not from this struct.
//
// Multi-value filters use IN-match semantics expressed as repeated query
// parameters (Gin's default for []string): `?statuses=ACTIVE&statuses=ON_LEAVE`
// matches rows where status is in {ACTIVE, ON_LEAVE}. Comma-separated values
// are NOT supported by Gin's binding and will be treated as a single string.
//
// binding tags validate each filter at bind time so malformed input returns
// a clean 400 rather than escalating to a 500 from the repo/DB layer.
type EmployeeSearchCriteria struct {
	IDs                   []string   `form:"ids" binding:"omitempty,dive,uuid"`
	Codes                 []string   `form:"codes" binding:"omitempty,dive,min=1,max=64"`
	Statuses              []string   `form:"statuses" binding:"omitempty,dive,min=1,max=64"`
	EmployeeTypes         []string   `form:"employeeTypes" binding:"omitempty,dive,min=1,max=128"`
	Departments           []string   `form:"departments" binding:"omitempty,dive,min=1,max=128"`
	Designations          []string   `form:"designations" binding:"omitempty,dive,min=1,max=128"`
	DateOfAppointmentFrom *time.Time `form:"dateOfAppointmentFrom" time_format:"2006-01-02"`
	DateOfAppointmentTo   *time.Time `form:"dateOfAppointmentTo" time_format:"2006-01-02"`
	IsActive              *bool      `form:"isActive"`
	// Role filters employees by a Keycloak realm role. Employee rows carry no
	// role column — the role lives in Keycloak against employee.user_id. The
	// service resolves this role to its Keycloak member user IDs and populates
	// UserIDs below; the repo then filters user_id IN (...). Pagination stays
	// at the DB so role composes with the other filters and limit/offset behave
	// consistently.
	Role string `form:"role" binding:"omitempty,min=1,max=256"`
	// UserIDs is NOT client-bindable (no form tag). It is populated internally
	// by the service after resolving Role against Keycloak. When non-empty the
	// repo adds a user_id IN (...) predicate.
	UserIDs []string `form:"-"`
	// Limit/Offset bounds mirror the OpenAPI spec and billing's convention.
	// min=1 catches limit=0 (which previously skipped the LIMIT clause in
	// the repo and returned all rows). max=100 prevents accidental large
	// scans. Negative offsets are rejected outright.
	Limit  int `form:"limit,default=10" binding:"min=1,max=100"`
	Offset int `form:"offset,default=0" binding:"min=0,max=2147483647"`
}
