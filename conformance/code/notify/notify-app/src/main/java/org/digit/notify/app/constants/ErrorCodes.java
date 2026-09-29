package org.digit.notify.app.constants;

/**
 * Error codes surfaced through the tracer's {@code CustomException}, which the tracer's
 * ExceptionAdvise renders as the platform's bare JSON array {@code [{code,message}]} and whose
 * {@code HttpStatus} it honours.
 *
 * <p>The values reproduce exactly what this service's own exception handler emitted before, so no
 * caller sees a different code than it did. What changed is the envelope: business errors used to
 * come back as a single object from a local handler while the tenant filter and /internal/migrate
 * returned an array, so one service answered in two shapes.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    /** Config, mapping or provider addressed by an id or code that does not exist. 404. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** A uniqueness rule already holds for this tenant — duplicate config or mapping. 409. */
    public static final String CONFLICT = "CONFLICT";

    /** The request is well-formed but asks for something invalid. 400. */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** Nothing is mapped at the requested path. 404, and deliberately not the tracer's 400. */
    public static final String NO_ENDPOINT = "NOT_FOUND";

    /** The database could not be reached or the statement failed. 500. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
