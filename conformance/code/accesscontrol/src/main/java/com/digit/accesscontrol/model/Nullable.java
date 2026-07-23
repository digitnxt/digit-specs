package com.digit.accesscontrol.model;

import tools.jackson.databind.JsonNode;

/**
 * Wraps an optional PATCH field so we can distinguish the three caller intents JSON conflates:
 * <ul>
 *   <li>Field absent          → set=false (don't touch the column)</li>
 *   <li>Field present as null  → set=true, isNull=true (clear the column)</li>
 *   <li>Field present w/ value → set=true, isNull=false, value=&lt;v&gt;</li>
 * </ul>
 * Mirrors Go internal/model/nullable.go (Nullable[T]). Presence/null are populated by the
 * controller from the raw JSON tree before the typed read, since Jackson cannot itself distinguish
 * "absent" from "null" for a default-constructed field.
 */
public class Nullable<T> {
    private boolean set;
    private boolean isNull;
    private T value;

    public boolean isSet() { return set; }
    public void setSet(boolean set) { this.set = set; }
    public boolean isNull() { return isNull; }
    public void setNull(boolean aNull) { this.isNull = aNull; }
    public T getValue() { return value; }
    public void setValue(T value) { this.value = value; }

    /**
     * Populates this wrapper from a raw JSON tree node for the given field, mirroring the Go
     * UnmarshalJSON semantics. {@code node} is the field's value node, or null when the field is
     * absent from the body.
     */
    public void populate(JsonNode node, java.util.function.Function<JsonNode, T> valueExtractor) {
        if (node == null) {
            this.set = false;
            return;
        }
        this.set = true;
        if (node.isNull()) {
            this.isNull = true;
            return;
        }
        this.value = valueExtractor.apply(node);
    }
}
