package com.digit.boundary.web;

import com.digit.boundary.constants.ErrorCodes;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Reproduces, byte-for-byte, the validation errors that the Go service returns from gin's
 * {@code ShouldBindJSON} (which runs the go-playground/validator on the request struct's
 * {@code binding:"..."} tags). Go emits these <em>during</em> body binding, i.e. before any header
 * or query checks, so the controllers invoke this immediately after a successful JSON parse and
 * before reading headers.
 *
 * <p>Message format matches go-playground/validator v10's {@code FieldError.Error()}:
 * {@code Key: '<Namespace>' Error:Field validation for '<Field>' failed on the '<tag>' tag},
 * with multiple field errors joined by {@code "\n"} (validator.ValidationErrors.Error()).
 */
final class GoBinding {

    private GoBinding() {}

    private static String fieldError(String namespace, String field, String tag) {
        return "Key: '" + namespace + "' Error:Field validation for '" + field + "' failed on the '" + tag + "' tag";
    }

    private static BoundaryApiException bindingError(String message) {
        return ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, message, "Invalid request payload");
    }

    /** True when a Go {@code required} string field would fail: missing/null or zero value (""). */
    private static boolean stringRequiredFails(JsonNode root, String jsonField) {
        if (root == null || !root.isObject()) {
            return true;
        }
        JsonNode n = root.get(jsonField);
        return n == null || n.isNull() || (n.isString() && n.asString().isEmpty());
    }

    /**
     * Validates {@code BoundaryRequest} exactly like Go (binding:"required,min=1" on Boundary).
     * Only the top-level slice is validated by gin; nested Boundary.Code is not (matches Go).
     *
     * @param root the raw parsed request body (object expected)
     */
    static void validateBoundaryRequest(JsonNode root) {
        JsonNode boundary = root != null && root.isObject() ? root.get("boundary") : null;
        if (boundary == null || boundary.isNull()) {
            throw bindingError(fieldError("BoundaryRequest.Boundary", "Boundary", "required"));
        }
        // present but not a non-empty array -> 'min' tag (Go's min=1)
        if (!boundary.isArray() || boundary.isEmpty()) {
            throw bindingError(fieldError("BoundaryRequest.Boundary", "Boundary", "min"));
        }
    }

    /** Validates a directly-bound {@code Boundary} (PUT /v3/boundaries/{id}) -> Code required. */
    static void validateBoundary(JsonNode root) {
        if (stringRequiredFails(root, "code")) {
            throw bindingError(fieldError("Boundary.Code", "Code", "required"));
        }
    }

    /** Validates a directly-bound {@code BoundaryRelationship} (PUT /v3/relationship/{id}). */
    static void validateRelationship(JsonNode root) {
        List<String> errs = relationshipFieldErrors(root, "BoundaryRelationship");
        if (!errs.isEmpty()) {
            throw bindingError(String.join("\n", errs));
        }
    }

    /** Validates {@code BoundaryRelationshipRequest} (POST /v3/relationship) -> nested Relationship. */
    static void validateRelationshipRequest(JsonNode root) {
        JsonNode rel = root != null && root.isObject() ? root.get("relationship") : null;
        List<String> errs = relationshipFieldErrors(rel, "BoundaryRelationshipRequest.Relationship");
        if (!errs.isEmpty()) {
            throw bindingError(String.join("\n", errs));
        }
    }

    private static List<String> relationshipFieldErrors(JsonNode rel, String namespacePrefix) {
        List<String> errs = new ArrayList<>();
        if (stringRequiredFails(rel, "code")) {
            errs.add(fieldError(namespacePrefix + ".Code", "Code", "required"));
        }
        if (stringRequiredFails(rel, "hierarchyType")) {
            errs.add(fieldError(namespacePrefix + ".HierarchyType", "HierarchyType", "required"));
        }
        if (stringRequiredFails(rel, "boundaryType")) {
            errs.add(fieldError(namespacePrefix + ".BoundaryType", "BoundaryType", "required"));
        }
        return errs;
    }
}
