package com.digit.employee.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical envelope handling: reads request context out of the RequestMetadata block and builds the
 * ResponseMetadata block, so canonical handlers take no request headers.
 */
final class CanonicalSupport {

    private static final List<String> METADATA_KEYS = List.of("RequestMetadata", "requestMetadata");
    private static final List<String> PAYLOAD_KEYS = List.of("data", "body", "request");

    private CanonicalSupport() {}

    /** The RequestMetadata block, or null when absent — callers turn that into a validation error. */
    static JsonNode requestMetadata(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String key : METADATA_KEYS) {
            JsonNode node = root.get(key);
            if (node != null && node.isObject()) {
                return node;
            }
        }
        return null;
    }

    /** The domain payload: a wrapper key when present, otherwise the root with the metadata stripped. */
    static JsonNode payload(JsonNode root) {
        if (root == null || !root.isObject()) {
            return root;
        }
        for (String key : PAYLOAD_KEYS) {
            JsonNode nested = root.get(key);
            if (nested != null) {
                return nested;
            }
        }
        ObjectNode copy = ((ObjectNode) root).deepCopy();
        copy.remove(METADATA_KEYS);
        return copy;
    }

    static <T> T payloadAs(ObjectMapper mapper, JsonNode root, Class<T> type) {
        return mapper.convertValue(payload(root), type);
    }

    static String tenantId(JsonNode metadata) {
        return text(metadata, "tenantId");
    }

    static String userId(JsonNode metadata) {
        JsonNode userInfo = metadata == null ? null : metadata.get("userInfo");
        String userId = firstText(userInfo, "uuid", "id", "userId", "userName", "username");
        return userId == null ? "" : userId;
    }

    static String requestId(JsonNode metadata) {
        String requestId = text(metadata, "requestId");
        return requestId == null ? "" : requestId;
    }

    static List<String> roles(JsonNode metadata) {
        JsonNode userInfo = metadata == null ? null : metadata.get("userInfo");
        if (userInfo == null || !userInfo.isObject()) {
            return null;
        }
        for (String key : List.of("roles", "roleCodes")) {
            JsonNode value = userInfo.get(key);
            if (value != null && value.isArray()) {
                List<String> roles = new ArrayList<>();
                value.forEach(role -> {
                    if (!role.asText("").isBlank()) {
                        roles.add(role.asText());
                    }
                });
                return roles;
            }
        }
        return null;
    }

    /** {@code {"ResponseMetadata": {...}, "data": <payload>}} — the one response shape for every endpoint. */
    static Map<String, Object> wrap(JsonNode metadata, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ResponseMetadata", responseMetadata(metadata, true));
        body.put("data", data);
        return body;
    }

    static Map<String, Object> responseMetadata(JsonNode metadata, boolean success) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ts", System.currentTimeMillis());
        copy(info, metadata, "msgId");
        copy(info, metadata, "requestId");
        copy(info, metadata, "correlationId");
        info.put("status", success ? "SUCCESSFUL" : "FAILED");
        return info;
    }

    private static void copy(Map<String, Object> target, JsonNode source, String field) {
        String value = text(source, field);
        if (value != null) {
            target.put(field, value);
        }
    }

    static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
