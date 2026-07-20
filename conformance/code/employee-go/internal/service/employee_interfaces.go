// internal/service/employee_interfaces.go
package service

import (
	"context"
	"employee/internal/models"
)

// EmployeeService defines the interface for employee operations.
//
// Request identity (tenantID, userID) is propagated via ctx — middleware
// validates the headers and stores them on the context. Implementations
// pull them out once at method entry via middleware.GetRequestContextFromContext.
type EmployeeService interface {
	// CreateEmployees creates one or more employees
	CreateEmployees(ctx context.Context, req []*models.CreateEmployeeRequest, authHeader string) ([]*models.EmployeeResponse, error)

	// SearchEmployees searches for employees based on criteria. authHeader is
	// forwarded to Keycloak only when criteria.Role is set (role → member
	// user-ID resolution); it may be empty for role-less searches.
	SearchEmployees(ctx context.Context, criteria *models.EmployeeSearchCriteria, authHeader string) ([]*models.EmployeeResponse, error)

	// GetEmployeeByUUID retrieves an employee by UUID
	GetEmployeeByUUID(ctx context.Context, uuid string) (*models.EmployeeResponse, error)

	// UpdateEmployee replaces an employee's mutable state via PUT semantics.
	// The full mutable set (employeeType, department, designation, status,
	// isActive, jurisdictions) and the current version are required. The write
	// compare-and-swaps on version (409 on staleness); jurisdictions are
	// reconciled against the supplied array (id+version → update, id-less →
	// insert, omitted → deactivate). Immutable fields cannot be changed.
	UpdateEmployee(ctx context.Context, uuid string, req *models.UpdateEmployeeRequest) (*models.EmployeeResponse, error)

	// HardDeleteEmployee permanently deletes an employee and all related records
	HardDeleteEmployee(ctx context.Context, uuid string) error

	// PatchEmployee partially updates an employee. Each mutable field is
	// optional (omitted → preserved); the current version is required and the
	// write compare-and-swaps on it. Jurisdictions, when supplied, are
	// reconciled against the array (empty array deactivates the collection).
	PatchEmployee(ctx context.Context, uuid string, req *models.PatchEmployeeRequest) (*models.EmployeeResponse, error)

	// DeactivateEmployee deactivates an employee
	DeactivateEmployee(ctx context.Context, uuid string) (*models.EmployeeResponse, error)

	// ReactivateEmployee reactivates an inactive employee
	ReactivateEmployee(ctx context.Context, uuid string) (*models.EmployeeResponse, error)
}
