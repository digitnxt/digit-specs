package errors

import "fmt"

// Error is the structured domain error carried up to the handler layer, which maps its Code to an
// HTTP status (httputil.StatusForCode) and returns Code/Message/Description to the client.
type Error struct {
	Code        string `json:"code"`
	Message     string `json:"message"`
	Description string `json:"description,omitempty"`
}

// Error implements the error interface.
func (e *Error) Error() string {
	if e.Description != "" {
		return fmt.Sprintf("%s: %s (%s)", e.Code, e.Message, e.Description)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

// New creates a domain error with the given code and message.
func New(code, message string) *Error {
	return &Error{Code: code, Message: message}
}

// WithDescription returns a COPY of the error with the description set. Copying keeps the shared
// predefined errors below (ErrNotFound, ...) immutable — callers do `ErrNotFound.WithDescription(...)`.
func (e *Error) WithDescription(desc string) *Error {
	c := *e
	c.Description = desc
	return &c
}

// Is reports whether err is an *Error carrying the same Code as target.
func Is(err error, target *Error) bool {
	e, ok := err.(*Error)
	if !ok {
		return false
	}
	return e.Code == target.Code
}

// Error code constants — the single place the wire-level code strings live. Clients depend on these
// values and httputil.StatusForCode maps them to HTTP statuses. Use these instead of inline string
// literals in New calls across the service.
const (
	// 400 — bad request / validation
	CodeValidation     = "VALIDATION_ERROR"
	CodeInvalidRequest = "INVALID_REQUEST"
	CodeInvalidInput   = "INVALID_INPUT"
	CodeInvalidUUID    = "INVALID_UUID"
	CodeBadRequest     = "BAD_REQUEST"
	CodeMissingHeader  = "MISSING_HEADER"

	// 401 / 403
	CodeUnauthorized = "UNAUTHORIZED"
	CodeForbidden    = "FORBIDDEN"

	// 404
	CodeNotFound             = "NOT_FOUND"
	CodeEmployeeNotFound     = "EMPLOYEE_NOT_FOUND"
	CodeJurisdictionNotFound = "JURISDICTION_NOT_FOUND"

	// 409 — conflict
	CodeConflict                = "CONFLICT"
	CodeRowVersionMismatch      = "ROW_VERSION_MISMATCH"
	CodeEmployeeExists          = "EMPLOYEE_EXISTS"
	CodeJurisdictionExists      = "JURISDICTION_EXISTS"
	CodeEmployeeAlreadyActive   = "EMPLOYEE_ALREADY_ACTIVE"
	CodeEmployeeAlreadyInactive = "EMPLOYEE_ALREADY_INACTIVE"
	CodeEmployeeDeactivated     = "EMPLOYEE_DEACTIVATED"

	// 502 — downstream dependency failure
	CodeDownstream = "DOWNSTREAM_ERROR"
	CodeBadGateway = "BAD_GATEWAY"

	// 500
	CodeDatabase = "DATABASE_ERROR"
	CodeInternal = "INTERNAL_ERROR"
)

// Common predefined errors, built from the code constants above.
var (
	// Generic errors
	ErrInternalServer = New(CodeInternal, "An internal server error occurred")
	ErrInvalidInput   = New(CodeInvalidInput, "Invalid input provided")
	ErrNotFound       = New(CodeNotFound, "The requested resource was not found")
	ErrUnauthorized   = New(CodeUnauthorized, "You are not authorized to perform this action")
	ErrForbidden      = New(CodeForbidden, "You don't have permission to access this resource")

	// Validation errors
	ErrValidationFailed = New(CodeValidation, "Validation failed")

	// Employee errors
	ErrEmployeeNotFound    = New(CodeEmployeeNotFound, "Employee not found")
	ErrEmployeeExists      = New(CodeEmployeeExists, "Employee already exists")
	ErrEmployeeDeactivated = New(CodeEmployeeDeactivated, "Employee account is deactivated")

	// Jurisdiction errors
	ErrJurisdictionNotFound = New(CodeJurisdictionNotFound, "Jurisdiction not found")
	ErrJurisdictionExists   = New(CodeJurisdictionExists, "Jurisdiction already exists")

	// Database errors
	ErrDatabase = New(CodeDatabase, "A database error occurred")
)
