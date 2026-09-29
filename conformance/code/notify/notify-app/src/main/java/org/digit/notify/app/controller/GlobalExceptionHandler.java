package org.digit.notify.app.controller;

import org.digit.notify.app.constants.ErrorCodes;
import org.digit.tracer.model.Error;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * The few exceptions the tracer's {@code ExceptionAdvise} does not map the way this service needs.
 * Everything else — above all {@code CustomException} — is left to the tracer, which already renders
 * the platform's bare {@code [{code,message}]} array and honours the status carried on the exception.
 *
 * <p>Deliberately no {@code @ExceptionHandler(Exception.class)} here. A catch-all would also match
 * {@code CustomException}, and because Spring consults one advice at a time this advice would then
 * answer every business error itself and the tracer would never see one. That is what used to happen:
 * a 404 came back as a single object from here while the tenant filter and {@code /internal/migrate}
 * answered with an array, so one service spoke two error dialects.
 *
 * <p>The three handlers that remain each exist because the tracer's fallback would be wrong, not
 * merely different. Its unhandled branch answers {@code 400} — {@code responseStatus} is initialised
 * to {@code BAD_REQUEST} and never reassigned — which would report an unmapped URL and a database
 * outage as the caller's fault.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Neither is mapped by the tracer, so both would land in its unhandled branch and lose their
     * message to "An unhandled exception occurred on the server". The status would be right; the
     * body would not say which field or which header.
     */
    @ExceptionHandler({ConstraintViolationException.class, MissingRequestHeaderException.class})
    public ResponseEntity<List<Error>> handleBadRequest(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, ex.getMessage());
    }

    /** An unmapped URL is the caller's wrong path, not a 400 and not a server fault. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<List<Error>> handleNoResource(HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, ErrorCodes.NO_ENDPOINT,
            "No endpoint " + req.getMethod() + " " + req.getRequestURI());
    }

    /**
     * Kept as 500 rather than left to the tracer's 400. A database that cannot be reached is not a
     * bad request, and with per-tenant schemas the most common cause is a tenant whose schema was
     * never created — so the reason is named here instead of leaving the caller a bare status.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<List<Error>> handleDataAccess(DataAccessException ex, HttpServletRequest req) {
        log.error("Database error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
            "The request could not be served by the database. If this tenant is new, its schema may "
                + "not exist yet — POST /internal/migrate with the tenant creates it.");
    }

    private static ResponseEntity<List<Error>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(List.of(new Error(code, message, null, null)));
    }
}
