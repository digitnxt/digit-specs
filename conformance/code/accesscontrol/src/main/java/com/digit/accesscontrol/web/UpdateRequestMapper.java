package com.digit.accesscontrol.web;

import com.digit.accesscontrol.model.UpdateJbacRuleRequest;
import com.digit.accesscontrol.model.UpdateRbacRuleRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds Update*Request objects from the parsed JSON tree, reproducing Go's json.Unmarshal into
 * pointer + Nullable[T] fields:
 *   - field absent              → leave ref null / Nullable.set=false (don't touch)
 *   - field present, non-null   → set the value
 *   - field present, JSON null  → for non-nullable fields it was already rejected upstream;
 *                                 for Nullable fields it records null (clear)
 *
 * If a present field's JSON type cannot be decoded into the target Go type, Go's json.Unmarshal
 * would fail the whole request — we surface that as the same "Failed to parse JSON request body"
 * 400 the handler returns.
 */
final class UpdateRequestMapper {
    private UpdateRequestMapper() {}

    static UpdateRbacRuleRequest rbac(ObjectMapper mapper, JsonNode body) {
        if (!body.isObject()) {
            throw ControllerSupport.invalidRequest("Failed to parse JSON request body");
        }
        UpdateRbacRuleRequest req = new UpdateRbacRuleRequest();
        try {
            if (present(body, "roleNames")) {
                req.setRoleNames(toStringList(body.get("roleNames")));
            }
            if (present(body, "httpMethod")) {
                req.setHttpMethod(body.get("httpMethod").asText());
            }
            if (present(body, "path")) {
                req.setPath(body.get("path").asText());
            }
            if (present(body, "effect")) {
                req.setEffect(body.get("effect").asText());
            }
            if (present(body, "priority")) {
                req.setPriority(body.get("priority").intValue());
            }
            if (present(body, "enabled")) {
                req.setEnabled(body.get("enabled").booleanValue());
            }
        } catch (RuntimeException e) {
            throw ControllerSupport.invalidRequest("Failed to parse JSON request body");
        }
        req.getConstraints().populate(body.get("constraints"), n -> n);
        req.getDescription().populate(body.get("description"), JsonNode::asText);
        return req;
    }

    static UpdateJbacRuleRequest jbac(ObjectMapper mapper, JsonNode body) {
        if (!body.isObject()) {
            throw ControllerSupport.invalidRequest("Failed to parse JSON request body");
        }
        UpdateJbacRuleRequest req = new UpdateJbacRuleRequest();
        try {
            if (present(body, "name")) {
                req.setName(body.get("name").asText());
            }
            if (present(body, "pathPattern")) {
                req.setPathPattern(body.get("pathPattern").asText());
            }
            if (present(body, "methods")) {
                req.setMethods(toStringList(body.get("methods")));
            }
            if (present(body, "enforcement")) {
                req.setEnforcement(body.get("enforcement").asText());
            }
            if (present(body, "parentImpliesChildren")) {
                req.setParentImpliesChildren(body.get("parentImpliesChildren").booleanValue());
            }
        } catch (RuntimeException e) {
            throw ControllerSupport.invalidRequest("Failed to parse JSON request body");
        }
        req.getExtractJurisdiction().populate(body.get("extractJurisdiction"), n -> n);
        req.getDescription().populate(body.get("description"), JsonNode::asText);
        return req;
    }

    /** True when the field exists and is not JSON null. */
    private static boolean present(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && !n.isNull();
    }

    private static List<String> toStringList(JsonNode arr) {
        if (!arr.isArray()) {
            throw new IllegalArgumentException("expected array");
        }
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode e : arr) {
            out.add(e.isNull() ? null : e.asText());
        }
        return out;
    }
}
