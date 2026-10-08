package com.digit.employee.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A database failure is the service's fault (500); data the database rejected stays a 400. */
class DatabaseExceptionHandlerTest {

    private final DatabaseExceptionHandler handler = new DatabaseExceptionHandler();

    @Test
    void anUnreachableDatabaseIsA500() {
        var resp = handler.handleDatabaseFailure(new CannotGetJdbcConnectionException("connection refused"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("DATABASE_ERROR", resp.getBody().get(0).getCode());
    }

    @Test
    void aRejectedValueStaysA400() {
        var resp = handler.handleIntegrityViolation(new DataIntegrityViolationException("value too long"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }
}
