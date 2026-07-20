package repository

import (
	"errors"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"
)

// Repository-level sentinel errors. Callers (services) branch on these via
// `errors.Is`, never by inspecting the raw Postgres error or its constraint
// name. The classifier in translatePgError keeps the database-specific
// matching contained here.
var (
	// ErrDuplicateEmployeeCode — a row with the same (tenantId, code) already
	// exists. Backs the `uk_employee_tenant_code_v3` unique constraint and
	// maps to HTTP 409 at the handler.
	ErrDuplicateEmployeeCode = errors.New("repository: employee code already exists")

	// ErrJurisdictionEmployeeNotFound — a jurisdiction INSERT failed because
	// the supplied employee_id does not exist. Backs the
	// `fk_jurisdiction_employee_v3` FK constraint and maps to HTTP 404 at the
	// handler. Tells the client they referenced a non-existent employee
	// rather than masking it as a generic 500 DATABASE_ERROR.
	ErrJurisdictionEmployeeNotFound = errors.New("repository: jurisdiction references unknown employee")
)

// translatePgError converts a raw Postgres error into one of the package-
// level sentinels for the constraint violations we care about. Anything
// else passes through unchanged so callers still see the original cause.
//
// SQLSTATEs handled:
//   - 23505 unique_violation     → resource-already-exists sentinels (409)
//   - 23503 foreign_key_violation → reference-not-found sentinels (404)
//
// Constraint names are substring-matched (case-insensitive) so the classifier
// survives migration renames as long as the schema still names the relevant
// column in the constraint identifier.
func translatePgError(err error) error {
	if err == nil {
		return nil
	}
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		return err
	}
	cn := strings.ToLower(pgErr.ConstraintName)
	switch pgErr.Code {
	case "23505": // unique_violation
		switch {
		case strings.Contains(cn, "code"):
			// `uk_employee_tenant_code_v3` from V20251126034400 — the
			// (tenant_id, code) composite uniqueness.
			return ErrDuplicateEmployeeCode
		}
	case "23503": // foreign_key_violation
		switch {
		case strings.Contains(cn, "employee"):
			// `fk_jurisdiction_employee_v3` from V20251126034400 — jurisdiction
			// rows must reference an existing employee row.
			return ErrJurisdictionEmployeeNotFound
		}
	}
	return err
}
