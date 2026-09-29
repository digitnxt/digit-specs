package com.digit.boundary.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Renders {@link BoundaryApiException} the way the Go handler does: the response body is a bare JSON
 * array of error objects ({@code [{code, message, description, params}]}) and the HTTP status is the
 * semantic status carried by the exception (400 / 404 / 409 / 500) — not the tracer's blanket 400
 * with an object wrapper. Ordered ahead of the tracer's global advice so this specific handler wins.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BoundaryExceptionHandler {

    @ExceptionHandler(BoundaryApiException.class)
    public ResponseEntity<List<ApiError>> handle(BoundaryApiException ex) {
        ApiError error = new ApiError(ex.getCode(), ex.getMessage(), ex.getDescription(), ex.getParams());
        return ResponseEntity.status(ex.getStatus()).body(List.of(error));
    }
}
