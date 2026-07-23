package com.digit.accesscontrol.web;

import com.digit.accesscontrol.constants.ErrorCodes;
import com.digit.accesscontrol.constants.Headers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.digit.tracer.model.CustomException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers shared by the controllers to reproduce the Go gin middleware + handler flow.
 *  Errors surface as the tracer's CustomException, which the tracer ExceptionAdvice renders as
 *  {@code {"Errors":[{code,message}]}} with HTTP 400. */
final class ControllerSupport {
    private ControllerSupport() {}

    /** Mirrors gin requireTenantID middleware: 400 if X-Tenant-ID is missing. */
    static String requireTenantId(HttpServletRequest request) {
        String tenantId = request.getHeader(Headers.TENANT_ID);
        if (tenantId == null || tenantId.isEmpty()) {
            throw new CustomException(ErrorCodes.MISSING_TENANT_ID, "X-Tenant-ID header is required");
        }
        return tenantId;
    }

    /** Mirrors gin requireUserID middleware: 400 if X-User-ID is missing. */
    static String requireUserId(HttpServletRequest request) {
        String userId = request.getHeader(Headers.USER_ID);
        if (userId == null || userId.isEmpty()) {
            throw new CustomException(ErrorCodes.MISSING_USER_ID, "X-User-ID header is required");
        }
        return userId;
    }

    static String header(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        return v == null ? "" : v;
    }

    /**
     * Parses the raw body into the target type. Malformed/empty JSON yields the Go ShouldBindJSON
     * error: 400 {AccessControl.InvalidRequest, "Failed to parse JSON request body"}.
     */
    static <T> T parseBody(ObjectMapper mapper, byte[] body, Class<T> type) {
        try {
            T parsed = mapper.readValue(body == null ? new byte[0] : body, type);
            if (parsed == null) {
                throw new RuntimeException("null body");
            }
            return parsed;
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "Failed to parse JSON request body");
        }
    }

    /** Parses the body into a JSON tree, or throws the parse-failure 400. */
    static JsonNode parseTree(ObjectMapper mapper, byte[] body) {
        try {
            JsonNode node = mapper.readTree(body == null ? new byte[0] : body);
            if (node == null || node.isMissingNode()) {
                throw new RuntimeException("empty body");
            }
            return node;
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "Failed to parse JSON request body");
        }
    }

    /** Throws a CustomException carrying the validation messages (ValidationFailed code) when non-empty. */
    static void failIfValidationErrors(List<String> msgs) {
        if (msgs != null && !msgs.isEmpty()) {
            throw new CustomException(validationMap(msgs));
        }
    }

    /**
     * Builds an ordered code-&gt;message map for a list of validation messages. Every entry uses the
     * {@code AccessControl.ValidationFailed} code (mirrors Go model.ValidationErrors); because
     * CustomException keys (codes) must be unique, a positional suffix is appended when there is more
     * than one message so no message is dropped.
     */
    static Map<String, String> validationMap(List<String> msgs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (msgs.size() == 1) {
            map.put(ErrorCodes.VALIDATION_FAILED, msgs.get(0));
        } else {
            for (int i = 0; i < msgs.size(); i++) {
                map.put(ErrorCodes.VALIDATION_FAILED + "." + (i + 1), msgs.get(i));
            }
        }
        return map;
    }

    /** 400 InvalidRequest with a custom message. */
    static CustomException invalidRequest(String message) {
        return new CustomException(ErrorCodes.INVALID_REQUEST, message);
    }

    /**
     * Rejects explicit JSON null on non-nullable fields. Mirrors util.RejectExplicitNulls: returns a
     * validation message for every listed field present in the body as JSON null. Returns empty when
     * the body is not a JSON object.
     */
    static List<String> rejectExplicitNulls(JsonNode body, List<String> nonNullable) {
        List<String> errs = new ArrayList<>();
        if (body == null || !body.isObject()) {
            return errs;
        }
        for (String field : nonNullable) {
            JsonNode v = body.get(field);
            if (v != null && v.isNull()) {
                errs.add(field + " cannot be null; omit the field or send a valid value");
            }
        }
        return errs;
    }

    /**
     * Binds an int query param with gin (binding:int) semantics: blank/absent → 0; otherwise
     * strconv.ParseInt. On failure surfaces the exact Go gin error
     * ("Invalid query parameter: strconv.ParseInt: parsing \"X\": invalid syntax").
     * No trimming — gin/strconv does not trim, so " 5 " is a parse failure like Go.
     * Parsed as 64-bit (Go's int is 64-bit on amd64) so out-of-range-but-parseable values like
     * "9999999999999" yield a 'max' validation error rather than a parse error.
     */
    static long bindInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalidRequest("Invalid query parameter: strconv.ParseInt: parsing "
                    + "\"" + raw + "\": invalid syntax");
        }
    }

    /**
     * Binds a *bool query param with gin (binding:bool) semantics: blank/absent → null;
     * otherwise strconv.ParseBool. strconv.ParseBool accepts 1,t,T,TRUE,true,True and
     * 0,f,F,FALSE,false,False. On failure surfaces the exact Go gin error.
     */
    static Boolean bindBool(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        switch (raw) {
            case "1": case "t": case "T": case "TRUE": case "true": case "True":
                return Boolean.TRUE;
            case "0": case "f": case "F": case "FALSE": case "false": case "False":
                return Boolean.FALSE;
            default:
                throw invalidRequest("Invalid query parameter: strconv.ParseBool: parsing "
                        + "\"" + raw + "\": invalid syntax");
        }
    }

    /**
     * Validation check mirroring gin binding:"min=...,max=..." backed by go-playground/validator.
     * Emits the validator's exact message:
     * "Key: '<Struct>.<Field>' Error:Field validation for '<Field>' failed on the '<tag>' tag".
     */
    static void validateRange(long value, int min, int max, String struct, String field) {
        if (value < min) {
            throw invalidRequest(validatorMessage(struct, field, "min"));
        }
        if (value > max) {
            throw invalidRequest(validatorMessage(struct, field, "max"));
        }
    }

    private static String validatorMessage(String struct, String field, String tag) {
        return "Invalid query parameter: Key: '" + struct + "." + field
                + "' Error:Field validation for '" + field + "' failed on the '" + tag + "' tag";
    }
}
