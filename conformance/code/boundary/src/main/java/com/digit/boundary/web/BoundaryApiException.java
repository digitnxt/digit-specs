package com.digit.boundary.web;

import java.util.List;

/**
 * Business/validation error carrying the exact HTTP status, error code, message, description and
 * params that the Go handler would emit. Rendered by {@link BoundaryExceptionHandler} as a bare JSON
 * array {@code [{code, message, description, params}]} with the carried status — mirroring Go's
 * {@code errorResponse(ctx, status, code, message, description, params)}.
 */
public class BoundaryApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String description;
    private final List<String> params;

    public BoundaryApiException(int status, String code, String message, String description, List<String> params) {
        super(message);
        this.status = status;
        this.code = code;
        this.description = description;
        this.params = params == null ? List.of() : params;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public List<String> getParams() { return params; }
}
