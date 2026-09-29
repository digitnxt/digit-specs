package com.digit.boundary.web;

/** Helpers shared by the controllers. Errors surface as a {@link BoundaryApiException}, which
 *  {@link BoundaryExceptionHandler} renders as a bare array {@code [{code,message,description,params}]}
 *  with the carried HTTP status — mirroring Go's {@code errorResponse(...)}. */
final class ControllerSupport {
    private ControllerSupport() {}

    /**
     * Builds a {@link BoundaryApiException} carrying the given HTTP status, code, message and
     * description (params default to {@code []}), matching the arguments Go passes to
     * {@code errorResponse}.
     */
    static BoundaryApiException error(int status, String code, String message, String description) {
        return new BoundaryApiException(status, code, message, description, null);
    }
}
