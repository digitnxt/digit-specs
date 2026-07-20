// Package constants holds validation limits and pagination bounds — the single source of truth for
// these values, mirroring the Java service's ValidationConstants. Values used in code (batch cap,
// keycloak page size, min appointment year) are referenced directly. The per-field length caps and
// pagination bounds are ALSO enforced by go-playground `binding:"..."` struct tags on the request
// DTOs (which must be string literals and so cannot reference these consts) — keep the tags in sync
// with the values here.
package constants

const (
	// MaxCreateBatch bounds how many employees one POST may create (OpenAPI maxItems).
	MaxCreateBatch = 100

	// KeycloakRoleMemberPageSize is the page size for the role-member lookup (first/max paging).
	KeycloakRoleMemberPageSize = 100

	// MinAppointmentYear — dateOfAppointment must not predate this year.
	MinAppointmentYear = 1900

	// Search pagination bounds (mirrored by the limit/offset binding tags).
	DefaultLimit = 10
	MinLimit     = 1
	MaxLimit     = 100
	MinOffset    = 0

	// Employee field length caps (mirrored by the binding tags; match employee_v3 column widths).
	EmployeeTypeMaxLen = 128
	DepartmentMaxLen   = 128
	DesignationMaxLen  = 128
	CodeMaxLen         = 64
	UserIDMaxLen       = 64
	IndividualIDMaxLen = 64
	StatusMaxLen       = 64
)
