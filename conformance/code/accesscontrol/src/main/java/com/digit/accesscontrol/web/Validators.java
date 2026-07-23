package com.digit.accesscontrol.web;

import com.digit.accesscontrol.constants.Constants;
import com.digit.accesscontrol.model.CreateJbacRuleRequest;
import com.digit.accesscontrol.model.CreateRbacRuleRequest;
import com.digit.accesscontrol.model.UpdateJbacRuleRequest;
import com.digit.accesscontrol.model.UpdateRbacRuleRequest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation logic ported verbatim from Go internal/validator/{rbac,jbac}_validator.go.
 * Returns validation message strings in the same order, with the same wording, as the Go validators.
 */
public final class Validators {
    private Validators() {}

    private static final Set<String> VALID_HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final String ALLOWED_METHODS_LABEL = "GET, POST, PUT, DELETE, PATCH";
    private static final Pattern STATIC_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern VALID_PARAM = Pattern.compile("^\\{param:(UUID|ALNUM)\\}$");
    private static final Pattern VALID_ROLE_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern VALID_UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Set<String> VALID_ENFORCEMENT = Set.of("REQUIRED", "OPTIONAL", "DISABLED");
    private static final String ALLOWED_ENFORCEMENT_LIST = "REQUIRED, OPTIONAL, DISABLED";
    private static final Pattern VALID_JBAC_NAME =
            Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9 _\\-]{0,62}[A-Za-z0-9])?$");

    // ===================== RBAC =====================

    public static List<String> validateRbacCreate(CreateRbacRuleRequest r) {
        List<String> errors = new ArrayList<>();
        errors.addAll(validateHTTPMethod(r.getHttpMethod()));
        errors.addAll(validateRoleNames(r.getRoleNames()));
        errors.addAll(validatePath(r.getPath()));
        errors.addAll(validateEffect(r.getEffect()));
        if (r.getPriority() != null) {
            errors.addAll(validatePriority(r.getPriority()));
        }
        errors.addAll(validateDescription(r.getDescription()));
        errors.addAll(validateConstraints(r.getConstraints()));
        return errors;
    }

    public static List<String> validateRbacUpdate(UpdateRbacRuleRequest r) {
        List<String> errors = new ArrayList<>();
        if (r.getHttpMethod() != null) {
            errors.addAll(validateHTTPMethod(r.getHttpMethod()));
        }
        if (r.getRoleNames() != null) {
            errors.addAll(validateRoleNames(r.getRoleNames()));
        }
        if (r.getPath() != null) {
            errors.addAll(validatePath(r.getPath()));
        }
        if (r.getEffect() != null) {
            errors.addAll(validateEffect(r.getEffect()));
        }
        if (r.getPriority() != null) {
            errors.addAll(validatePriority(r.getPriority()));
        }
        if (r.getDescription().isSet() && !r.getDescription().isNull()) {
            errors.addAll(validateDescription(r.getDescription().getValue()));
        }
        if (r.getConstraints().isSet() && !r.getConstraints().isNull()) {
            errors.addAll(validateConstraints(r.getConstraints().getValue()));
        }
        return errors;
    }

    private static List<String> validateHTTPMethod(String method) {
        if (method == null || method.isEmpty()) {
            return List.of("httpMethod is required");
        }
        if (!VALID_HTTP_METHODS.contains(method)) {
            return List.of(String.format("Invalid httpMethod %s. Allowed values: %s",
                    quote(method), ALLOWED_METHODS_LABEL));
        }
        return List.of();
    }

    private static List<String> validateRoleNames(List<String> roleNames) {
        List<String> errors = new ArrayList<>();
        if (roleNames == null || roleNames.isEmpty()) {
            errors.add("roleNames must be a non-empty array");
            return errors;
        }
        if (roleNames.size() > Constants.MAX_ROLE_NAMES_PER_RULE) {
            errors.add(String.format("roleNames must contain at most %d entries (got %d)",
                    Constants.MAX_ROLE_NAMES_PER_RULE, roleNames.size()));
        }
        for (String roleName : roleNames) {
            if (roleName == null || roleName.isEmpty()) {
                errors.add("roleNames cannot contain empty strings");
                continue;
            }
            if (roleName.equals("*")) {
                errors.add("roleNames cannot contain wildcard '*'");
                continue;
            }
            if (roleName.length() > Constants.MAX_ROLE_NAME_LENGTH) {
                errors.add(String.format("Invalid role name %s: must be at most %d characters",
                        quote(roleName), Constants.MAX_ROLE_NAME_LENGTH));
                continue;
            }
            if (!VALID_ROLE_NAME.matcher(roleName).matches()) {
                errors.add(String.format("Invalid role name: %s. Role names must contain only "
                        + "alphanumeric characters, underscores, and hyphens "
                        + "(no spaces or special characters)", roleName));
            }
            if (!roleName.equals(roleName.strip())) {
                errors.add(String.format("Invalid role name: %s. Role names cannot have leading "
                        + "or trailing spaces", roleName));
            }
        }
        return errors;
    }

    private static List<String> validatePath(String path) {
        List<String> errors = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return List.of("path is required");
        }
        if (path.length() > Constants.MAX_PATH_LENGTH) {
            errors.add(String.format("path must be at most %d characters (got %d)",
                    Constants.MAX_PATH_LENGTH, path.length()));
            return errors;
        }
        if (!path.startsWith("/")) {
            errors.add("Path must start with '/'");
        }
        if (path.contains("?")) {
            errors.add("Path must NOT contain '?' (query parameters not allowed)");
        }
        if (path.contains("#")) {
            errors.add("Path must NOT contain '#' (fragments not allowed)");
        }
        if (path.contains("//")) {
            errors.add("Path must NOT contain '//' (double slashes not allowed)");
        }
        String[] segments = path.split("/", -1);
        int nonEmptySegments = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }
            nonEmptySegments++;
            boolean isLastSegment = i == segments.length - 1;
            if (segment.equals("*")) {
                if (!isLastSegment) {
                    errors.add("Wildcard '*' is only allowed as the last segment");
                }
            } else if (segment.startsWith("{") && segment.endsWith("}")) {
                if (!VALID_PARAM.matcher(segment).matches()) {
                    errors.add(String.format("Invalid parameter format: %s. Must be {param:UUID} "
                            + "or {param:ALNUM}", segment));
                }
            } else {
                if (!STATIC_SEGMENT.matcher(segment).matches()) {
                    errors.add(String.format("Invalid static segment: %s. Must contain only "
                            + "[A-Za-z0-9_-]", segment));
                }
            }
        }
        if (nonEmptySegments == 0) {
            errors.add("path must contain at least one segment after '/'");
        }
        if (nonEmptySegments > Constants.MAX_PATH_SEGMENTS) {
            errors.add(String.format("path must contain at most %d segments (got %d)",
                    Constants.MAX_PATH_SEGMENTS, nonEmptySegments));
        }
        return errors;
    }

    private static List<String> validateEffect(String effect) {
        if (effect == null || effect.isEmpty()) {
            return List.of("effect is required");
        }
        if (!effect.equals("ALLOW") && !effect.equals("DENY")) {
            return List.of(String.format("Invalid effect %s. Allowed values: ALLOW, DENY", quote(effect)));
        }
        return List.of();
    }

    private static List<String> validatePriority(int priority) {
        if (priority < 0) {
            return List.of(String.format("Priority must be a non-negative integer (got %d). "
                    + "Priority cannot be negative", priority));
        }
        if (priority > Constants.MAX_PRIORITY) {
            return List.of(String.format("Priority must be at most %d (got %d)",
                    Constants.MAX_PRIORITY, priority));
        }
        return List.of();
    }

    private static List<String> validateDescription(String description) {
        if (description != null && description.length() > Constants.MAX_DESCRIPTION_LENGTH) {
            return List.of(String.format("description must be at most %d characters (got %d)",
                    Constants.MAX_DESCRIPTION_LENGTH, description.length()));
        }
        return List.of();
    }

    private static List<String> validateConstraints(JsonNode constraints) {
        return validateJSONField("constraints", constraints);
    }

    /**
     * Checks size + parseability of a JSONB column payload. Mirrors Go validateJSONField.
     * Size is measured as the byte length of the JSON value (compact serialization), and an empty
     * (absent) value is skipped.
     */
    static List<String> validateJSONField(String fieldName, JsonNode raw) {
        if (raw == null || raw.isMissingNode()) {
            return List.of();
        }
        byte[] bytes = raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return List.of();
        }
        if (bytes.length > Constants.MAX_JSON_FIELD_BYTES) {
            return List.of(String.format("%s must be at most %d bytes (got %d)",
                    fieldName, Constants.MAX_JSON_FIELD_BYTES, bytes.length));
        }
        // raw came from a parsed tree, so it is already valid JSON.
        if (!raw.isObject()) {
            return List.of(String.format("%s must be a JSON object", fieldName));
        }
        return List.of();
    }

    public static List<String> validateRuleID(String id) {
        List<String> errors = new ArrayList<>();
        if (id == null || id.isEmpty()) {
            errors.add("Rule ID cannot be empty");
            return errors;
        }
        if (!VALID_UUID.matcher(id.toLowerCase()).matches()) {
            errors.add(String.format("Invalid rule ID format: %s. Rule ID must be a valid UUID "
                    + "(e.g., 550e8400-e29b-41d4-a716-446655440000)", id));
        }
        return errors;
    }

    // ===================== JBAC =====================

    public static List<String> validateJbacCreate(CreateJbacRuleRequest r) {
        List<String> errors = new ArrayList<>();
        errors.addAll(validateJbacName(r.getName()));
        errors.addAll(validateJbacMethods(r.getMethods()));
        errors.addAll(validatePath(r.getPathPattern()));
        errors.addAll(validateEnforcement(r.getEnforcement()));
        errors.addAll(validateDescription(r.getDescription()));
        errors.addAll(validateJSONField("extractJurisdiction", r.getExtractJurisdiction()));
        return errors;
    }

    public static List<String> validateJbacUpdate(UpdateJbacRuleRequest r) {
        List<String> errors = new ArrayList<>();
        if (r.getName() != null) {
            errors.addAll(validateJbacName(r.getName()));
        }
        if (r.getMethods() != null) {
            errors.addAll(validateJbacMethods(r.getMethods()));
        }
        if (r.getPathPattern() != null) {
            errors.addAll(validatePath(r.getPathPattern()));
        }
        if (r.getEnforcement() != null) {
            errors.addAll(validateEnforcement(r.getEnforcement()));
        }
        if (r.getDescription().isSet() && !r.getDescription().isNull()) {
            errors.addAll(validateDescription(r.getDescription().getValue()));
        }
        if (r.getExtractJurisdiction().isSet() && !r.getExtractJurisdiction().isNull()) {
            errors.addAll(validateJSONField("extractJurisdiction", r.getExtractJurisdiction().getValue()));
        }
        return errors;
    }

    private static List<String> validateJbacName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return List.of("name is required");
        }
        if (trimmed.length() > Constants.MAX_ROLE_NAME_LENGTH) {
            return List.of(String.format("name must be at most %d characters (got %d)",
                    Constants.MAX_ROLE_NAME_LENGTH, trimmed.length()));
        }
        if (!VALID_JBAC_NAME.matcher(trimmed).matches()) {
            return List.of("name must start and end with an alphanumeric character and may only "
                    + "contain letters, digits, spaces, underscores, or hyphens");
        }
        return List.of();
    }

    private static List<String> validateJbacMethods(List<String> methods) {
        List<String> errors = new ArrayList<>();
        if (methods == null || methods.isEmpty()) {
            return List.of("methods must be a non-empty array");
        }
        if (methods.size() > Constants.MAX_ROLE_NAMES_PER_RULE) {
            errors.add(String.format("methods must contain at most %d entries (got %d)",
                    Constants.MAX_ROLE_NAMES_PER_RULE, methods.size()));
        }
        for (String m : methods) {
            errors.addAll(validateHTTPMethod(m));
        }
        return errors;
    }

    private static List<String> validateEnforcement(String enforcement) {
        if (enforcement == null || enforcement.isEmpty()) {
            return List.of("enforcement is required");
        }
        if (!VALID_ENFORCEMENT.contains(enforcement)) {
            return List.of(String.format("Invalid enforcement %s. Allowed values: %s",
                    quote(enforcement), ALLOWED_ENFORCEMENT_LIST));
        }
        return List.of();
    }

    /** Mirrors Go's %q quoting (double-quoted with Go escaping). For typical method/effect tokens
     *  this is just surrounding double quotes. */
    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
