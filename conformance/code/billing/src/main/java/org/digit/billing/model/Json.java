package org.digit.billing.model;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mapper for JSONB columns and row hashing — deliberately NOT a Spring bean
 * (a JsonMapper bean would displace Boot's auto-configured HTTP mapper).
 * Go wrote nil slices/maps as [] / {} and read SQL NULL as empty — same here.
 */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private Json() {
    }

    public static String writeArray(List<String> value) {
        return MAPPER.writeValueAsString(value == null ? List.of() : value);
    }

    public static String writeMap(Map<String, Object> value) {
        return MAPPER.writeValueAsString(value == null ? Map.of() : value);
    }

    public static List<String> readArray(String json) {
        return json == null ? List.of() : MAPPER.readValue(json, STRING_LIST);
    }

    public static Map<String, Object> readMap(String json) {
        return json == null ? Map.of() : MAPPER.readValue(json, OBJECT_MAP);
    }
}
