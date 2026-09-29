package com.digit.boundary.web;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A single error object, mirroring Go {@code internal/common/models.Error}
 * ({@code {code, message, description, params}}). Error responses are serialized as a bare JSON
 * array of these objects, matching the Go handler's {@code ctx.JSON(status, []Error{...})}.
 */
@JsonPropertyOrder({"code", "message", "description", "params"})
public class ApiError {

    private final String code;
    private final String message;
    private final String description;
    private final List<String> params;

    public ApiError(String code, String message, String description, List<String> params) {
        this.code = code;
        this.message = message;
        this.description = description;
        // Go always emits params as [] (never null).
        this.params = params == null ? List.of() : params;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getDescription() { return description; }
    public List<String> getParams() { return params; }
}
