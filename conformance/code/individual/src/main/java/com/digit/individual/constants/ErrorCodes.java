package com.digit.individual.constants;

/** Error codes and pagination constants. Mirrors Go internal/common/constants.go. */
public final class ErrorCodes {
    private ErrorCodes() {}

    // Identifier types
    public static final String IDENTIFIER_TYPE_AADHAAR = "AADHAAR";
    public static final String IDENTIFIER_TYPE_SYSTEM_GENERATED = "SYSTEM_GENERATED";

    // Error codes — wire-level constants; clients depend on these strings.
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String MISSING_HEADER = "MISSING_HEADER";
    public static final String NON_EXISTENT_ENTITY = "NOT_FOUND";
    public static final String UNIQUE_ENTITY = "UNIQUE_ENTITY_ERROR";
    // DB-level unique-constraint violation (Postgres 23505) — the race backstop behind the
    // app-level UNIQUE_ENTITY pre-check. Surfaced as 409 so tracer does not report it as a 500.
    public static final String DUPLICATE = "DUPLICATE_ERROR";
    public static final String ROW_VERSION_MISMATCH = "ROW_VERSION_MISMATCH";
    public static final String DATABASE = "DATABASE_ERROR";
    public static final String FAILED_TO_HASH = "FAILED_TO_HASH";
    public static final String ENCRYPTION = "ENCRYPTION_ERROR";
    public static final String DECRYPTION = "DECRYPTION_ERROR";

    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    // Page-based pagination
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
}
