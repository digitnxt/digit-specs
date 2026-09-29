package com.digit.account.web;

import org.digit.tracer.model.Error;

import java.util.List;

/**
 * Carries N validation errors that must render as a bare {@code [{code,message}]} array where every
 * entry shares the same {@code Account.ValidationFailed} code (mirrors Go
 * {@code models.ValidationErrors}). The tracer {@code CustomException(Map)} constructor keys errors
 * by code and so cannot hold duplicate codes; this exception is rendered directly by
 * {@link ValidationExceptionHandler}.
 */
public class ValidationException extends RuntimeException {

    private final transient List<Error> errors;

    public ValidationException(List<Error> errors) {
        super("validation failed");
        this.errors = errors;
    }

    public List<Error> getErrors() {
        return errors;
    }
}