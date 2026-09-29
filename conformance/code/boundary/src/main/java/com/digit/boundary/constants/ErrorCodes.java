package com.digit.boundary.constants;

/** Error codes used in boundary error responses. Mirrors the codes used in the Go handlers. */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String CONFLICT = "CONFLICT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
}
