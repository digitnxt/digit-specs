package com.digit.individual.web;

import com.digit.individual.constants.ErrorCodes;
import org.digit.tracer.model.Error;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Database failures, which the tracer's central advice would report as 400. A database that is
 * unreachable, times out or fails a statement is the service's fault, so it is a 500. A constraint
 * the request's data broke (and no repository already translated) stays a 400. Runs at
 * HIGHEST_PRECEDENCE so it wins over the tracer advice.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<List<Error>> handleIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("database rejected the request's data", ex);
        return ResponseEntity.badRequest().body(List.of(
                new Error("QUERY_EXECUTION_ERROR", "Database operation failed", null, null)));
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<List<Error>> handleDatabaseFailure(Exception ex) {
        log.error("database operation failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of(
                new Error(ErrorCodes.DATABASE, "Database operation failed", null, null)));
    }
}
