package org.digit.idgen.model;

/** Error codes of the idgen API contract (identical to the Go implementation). */
public final class ErrorCodes {

    public static final String MISSING_HEADER = "MISSING_HEADER";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INVALID_PARAM = "INVALID_PARAM";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String CONFLICT = "CONFLICT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNPROCESSABLE = "UNPROCESSABLE";

    private ErrorCodes() {
    }
}
