package common

const (
	// Identifier types
	IdentifierTypeAadhaar         = "AADHAAR"
	IdentifierTypeSystemGenerated = "SYSTEM_GENERATED"

	// Error codes — kept in sync with the predefined CustomError vars in errors.go.
	// Wire-level constants — clients depend on these strings.
	ErrorValidation        = "VALIDATION_ERROR"
	ErrorMissingHeader     = "MISSING_HEADER"
	ErrorNonExistentEntity = "NOT_FOUND" // Go const name kept for back-compat; wire value is NOT_FOUND.
	ErrorUniqueEntity      = "UNIQUE_ENTITY_ERROR"
	ErrorDuplicate         = "DUPLICATE_ERROR" // DB unique-violation (23505) race backstop

	ErrorRowVersionMismatch = "ROW_VERSION_MISMATCH"
	ErrorDatabase           = "DATABASE_ERROR"
	ErrorDownstream         = "DOWNSTREAM_ERROR" // a dependency call (e.g. idgen) failed → 502
	ErrorInternal           = "INTERNAL_ERROR"   // catch-all for unclassified errors → 500
	ErrorFailedToHash       = "FAILED_TO_HASH"
	ErrorEncryption         = "ENCRYPTION_ERROR"
	ErrorDecryption         = "DECRYPTION_ERROR"

	// Page-based pagination
	DefaultPage     = 1
	DefaultPageSize = 20
	MaxPageSize     = 100
)
