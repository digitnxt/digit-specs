package common

import (
	"errors"
	"fmt"
)

// CustomError is the service-layer error type carried up to handlers, which
// translate it into the spec-shaped HTTP response (status + []Error envelope).
// Code drives status mapping; Message/Description surface on the response.
// The wire Error carries no `params` — matching the platform Error contract
// and the Java individual service.
type CustomError struct {
	Code        string
	Message     string
	Description string
}

func (e *CustomError) Error() string {
	return fmt.Sprintf("[%s] %s: %s", e.Code, e.Message, e.Description)
}

// newCustomError builds a CustomError. Unexported because the predefined
// errors below are the only legitimate constructors — callers pick one and
// chain .WithContext(...) to specialise the message.
func newCustomError(code, message, description string) *CustomError {
	return &CustomError{
		Code:        code,
		Message:     message,
		Description: description,
	}
}

// WithContext returns a copy of the error, promoting a "message" entry (when
// present) to the top-level Message so the caller-specific text surfaces on the
// response. Other keys are call-site context only — they are NOT emitted on the
// wire (error responses carry code/message/description, never params).
func (e *CustomError) WithContext(ctx map[string]interface{}) *CustomError {
	ne := &CustomError{Code: e.Code, Message: e.Message, Description: e.Description}
	if m, ok := ctx["message"].(string); ok && m != "" {
		ne.Message = m
	}
	return ne
}

// Predefined errors. Only the codes the service actually emits live here;
// status mapping is in handlers.handleServiceError.
var (
	ErrValidation = newCustomError(
		ErrorValidation,
		"Validation failed",
		"One or more validation checks failed",
	)

	ErrNonExistentEntity = newCustomError(
		ErrorNonExistentEntity,
		"Individual not found",
		"The requested individual was not found",
	)

	ErrUniqueEntity = newCustomError(
		ErrorUniqueEntity,
		"Duplicate entity",
		"An entity with the same unique identifiers already exists",
	)

	// ErrDuplicate is the DB-level unique-constraint (Postgres 23505) backstop behind the
	// app-level uniqueness check. Translated in the repository and mapped to 409 by the handler.
	ErrDuplicate = newCustomError(
		ErrorDuplicate,
		"Duplicate value violates unique constraint",
		"A row with the same unique key already exists",
	)

	ErrRowVersionMismatch = newCustomError(
		ErrorRowVersionMismatch,
		"Row version mismatch",
		"The entity has been modified by another user",
	)

	ErrDatabase = newCustomError(
		ErrorDatabase,
		"Database error",
		"An error occurred while accessing the database",
	)

	// ErrDownstream is a dependency-call failure (e.g. idgen). Mapped to 502 —
	// not the client's fault. Chain .WithContext({"message": ...}) with the
	// specific cause.
	ErrDownstream = newCustomError(
		ErrorDownstream,
		"Downstream service error",
		"A dependency call failed",
	)

	// ErrInternal is the catch-all for unclassified errors → 500. Used by the
	// handler when it receives a non-CustomError.
	ErrInternal = newCustomError(
		ErrorInternal,
		"Internal server error",
		"An unexpected error occurred",
	)

	ErrFailedToHash = newCustomError(
		ErrorFailedToHash,
		"Failed to hash",
		"Failed to hash mobile number",
	)

	ErrEncryption = newCustomError(
		ErrorEncryption,
		"Encryption failed",
		"Failed to encrypt sensitive data",
	)

	ErrDecryption = newCustomError(
		ErrorDecryption,
		"Decryption failed",
		"Failed to decrypt sensitive data",
	)
)

// ErrOptimisticLock is a sentinel returned by repositories when a version-guarded
// (compare-and-swap) update matches no row — i.e. the row changed since it was
// read. Callers translate it into ErrRowVersionMismatch (HTTP 409).
var ErrOptimisticLock = errors.New("optimistic lock: row version changed")
