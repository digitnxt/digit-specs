package com.digit.employee.web;

import com.digit.employee.constants.ErrorCodes;
import com.digit.employee.constants.ValidationConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;

import java.util.UUID;

/** Small helpers shared by the controllers. Errors surface as a tracer {@code CustomException}
 *  (carrying code + message + HttpStatus); the tracer's ExceptionAdvice renders the response
 *  ({@code {"errors":[...]}}) with the carried status. */
final class ControllerSupport {
    private ControllerSupport() {}

    /**
     * Parses the raw request body into the target type. An empty body or malformed JSON yields an
     * INVALID_REQUEST CustomException, mirroring the Go ShouldBindJSON error path
     * ({@code errors.New(ErrorCodes.INVALID_REQUEST, err.Error())}).
     */
    static <T> T parseBody(ObjectMapper mapper, byte[] body, Class<T> type) {
        if (body == null || body.length == 0) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "EOF");
        }
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
    }

    static <T> T parseBody(ObjectMapper mapper, byte[] body, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        if (body == null || body.length == 0) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "EOF");
        }
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
    }

    /** Enforces search pagination bounds (Go binding: limit 1..100, offset >= 0) → 400 otherwise. */
    static void validatePaging(int limit, int offset) {
        if (limit < ValidationConstants.MIN_LIMIT || limit > ValidationConstants.MAX_LIMIT) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                    "limit must be between " + ValidationConstants.MIN_LIMIT + " and " + ValidationConstants.MAX_LIMIT);
        }
        if (offset < ValidationConstants.MIN_OFFSET) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                    "offset must be >= " + ValidationConstants.MIN_OFFSET);
        }
    }

    /** Validates a path UUID, throwing INVALID_UUID with the given message on failure. */
    static void requireUUID(String value, String message) {
        try {
            UUID.fromString(value);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.INVALID_UUID, message);
        }
    }
}
