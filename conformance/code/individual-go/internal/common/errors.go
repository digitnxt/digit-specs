package common

import (
	"errors"
	"fmt"
)

// CustomError is the service-layer error type carried up to handlers, which
// translate it into the spec-shaped HTTP response (status + Error envelope).
// Code drives status mapping; Description and Params surface as response
// fields when set.
type CustomError struct {
	Code        string
	Message     string
	Description string
	Params      map[string]interface{}
}

func (e *CustomError) Error() string {
	return fmt.Sprintf("[%s] %s: %s", e.Code, e.Message, e.Description)
}

// newCustomError builds a CustomError. Unexported because the predefined
// errors below are the only legitimate constructors — callers should pick
// one and chain .WithParams(...) for context.
func newCustomError(code, message, description string) *CustomError {
	return &CustomError{
		Code:        code,
		Message:     message,
		Description: description,
		Params:      make(map[string]interface{}),
	}
}

// WithParams attaches contextual key/values that surface on the API response
// as a string-array under `params`.
func (e *CustomError) WithParams(params map[string]interface{}) *CustomError {
	return &CustomError{
		Code:        e.Code,
		Message:     e.Message,
		Description: e.Description,
		Params:      params,
	}
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
