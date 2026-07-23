package repository

import (
	"errors"
	"fmt"
)

// ErrNotFound is returned when a requested entity is not found in the database.
var ErrNotFound = errors.New("not found")

// ErrDuplicateKey is returned when attempting to create a resource that already exists
var ErrDuplicateKey = errors.New("duplicate key violation")

// ErrDatabaseConnection is returned when there's a database connection issue
var ErrDatabaseConnection = errors.New("database connection error")

// WrapDatabaseError wraps database errors with context
func WrapDatabaseError(operation string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%s: %w", operation, err)
}
