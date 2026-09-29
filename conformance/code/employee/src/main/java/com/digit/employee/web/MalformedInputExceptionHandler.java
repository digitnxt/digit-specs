package com.digit.employee.web;

import com.digit.employee.constants.ErrorCodes;
import org.digit.tracer.model.Error;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Durable handler for malformed-request classes the tracer's ExceptionAdvice would otherwise report
 * as 500. Runs at HIGHEST_PRECEDENCE so it wins over the tracer advice (LOWEST_PRECEDENCE); returns
 * the same bare {@code []ApiError} envelope. Mirrors the Go reference (400 for such input).
 *
 * <ul>
 *   <li>type mismatch on a typed query param (bad boolean/number/date) → 400</li>
 *   <li>unreadable / malformed request body → 400</li>
 * </ul>
 *
 * <p>Unsupported HTTP methods are intentionally not handled here — the API gateway rejects them.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MalformedInputExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<List<Error>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("invalid value for query parameter '" + ex.getName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<List<Error>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return badRequest("request body could not be parsed");
    }

    // Malformed query string (e.g. `?=` — empty parameter name) → Tomcat throws this lazily during
    // @RequestParam binding; without this it hits the tracer's catch-all → 500. → 400.
    @ExceptionHandler(org.apache.tomcat.util.http.InvalidParameterException.class)
    public ResponseEntity<List<Error>> handleMalformedQuery(org.apache.tomcat.util.http.InvalidParameterException ex) {
        return badRequest("malformed query string");
    }

    private static ResponseEntity<List<Error>> badRequest(String message) {
        return ResponseEntity.badRequest().body(List.of(new Error(ErrorCodes.INVALID_REQUEST, message, null, null)));
    }
}
