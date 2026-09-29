package com.digit.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.model.Error;

import java.util.ArrayList;
import java.util.List;

/** Shared controller helpers reproducing the Go handler flow (ShouldBindJSON + multi-error 400).
 *  Validation failures surface as a {@link ValidationException} rendered as a bare
 *  {@code [{code,message}]} array (HTTP 400); other errors use the tracer's CustomException. */
final class ControllerSupport {
    private ControllerSupport() {}

    /** Validation error code carried by every field-level validation failure (mirrors Go Account.ValidationFailed). */
    static final String VALIDATION_FAILED = "Account.ValidationFailed";

    /**
     * Parses the raw request body into the target type. An empty body ("EOF") or malformed JSON
     * yields a {@code BAD_REQUEST} CustomException (with the underlying message), mirroring
     * the Go {@code c.ShouldBindJSON} failure path. A literal JSON {@code null} decodes to
     * {@code null}, which the validators reject with "request body is required" (matching Go's
     * zero-value bind + validation).
     */
    static <T> T parseBody(ObjectMapper mapper, byte[] body, Class<T> type) {
        if (body == null || body.length == 0) {
            throw new CustomException("BAD_REQUEST", "Invalid request body: EOF");
        }
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException("BAD_REQUEST", "Invalid request body: " + e.getMessage());
        }
    }

    /**
     * Throws a {@link ValidationException} carrying one tracer {@link Error} per message when
     * non-empty. Every entry uses the same {@code Account.ValidationFailed} code — matching Go's
     * {@code models.ValidationErrors} (a bare array where the code repeats).
     */
    static void failIfValidation(List<String> errs) {
        if (errs != null && !errs.isEmpty()) {
            List<Error> out = new ArrayList<>(errs.size());
            for (String m : errs) {
                out.add(new Error(VALIDATION_FAILED, m, null, null));
            }
            throw new ValidationException(out);
        }
    }
}