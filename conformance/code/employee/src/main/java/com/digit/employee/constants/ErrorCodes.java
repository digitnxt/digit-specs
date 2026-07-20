package com.digit.employee.constants;

/**
 * Wire-level error codes — the single place these strings live. Clients depend on these values; they
 * are thrown via the tracer {@code CustomException(code, message, HttpStatus)} and rendered by the
 * tracer's ExceptionAdvice. Mirrors the codes in Go internal/httputil/response.go and pkg/errors.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    // 400 — bad request / validation
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String INVALID_UUID = "INVALID_UUID";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String MISSING_HEADER = "MISSING_HEADER";

    // 401 / 403
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";

    // 404
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String EMPLOYEE_NOT_FOUND = "EMPLOYEE_NOT_FOUND";
    public static final String JURISDICTION_NOT_FOUND = "JURISDICTION_NOT_FOUND";

    // 409 — conflict
    public static final String CONFLICT = "CONFLICT";
    public static final String ROW_VERSION_MISMATCH = "ROW_VERSION_MISMATCH";
    public static final String EMPLOYEE_EXISTS = "EMPLOYEE_EXISTS";
    public static final String JURISDICTION_EXISTS = "JURISDICTION_EXISTS";
    public static final String EMPLOYEE_ALREADY_ACTIVE = "EMPLOYEE_ALREADY_ACTIVE";
    public static final String EMPLOYEE_ALREADY_INACTIVE = "EMPLOYEE_ALREADY_INACTIVE";

    // 502 — downstream dependency failure (Keycloak / Individual / Boundary / IDGen)
    public static final String DOWNSTREAM_ERROR = "DOWNSTREAM_ERROR";
    public static final String BAD_GATEWAY = "BAD_GATEWAY";

    // 500
    public static final String DATABASE_ERROR = "DATABASE_ERROR";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}