package com.digit.account.web;

import org.digit.tracer.model.Error;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Renders {@link ValidationException} as a bare {@code List<Error>} with HTTP 400, matching Go's
 * {@code models.ValidationErrors} output where every entry carries the same
 * {@code Account.ValidationFailed} code. Runs at HIGHEST_PRECEDENCE so it wins over the tracer
 * central advice.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidationExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<List<Error>> handle(ValidationException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }
}
