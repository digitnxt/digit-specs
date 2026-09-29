package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One level in a boundary type hierarchy. Mirrors Go internal/models/hierarchy.BoundaryTypeHierarchy:
 * {@code id,omitempty}, {@code boundaryType,omitempty}, {@code parentBoundaryType} (nullable, always
 * serialized) and {@code active} (always serialized).
 */
@JsonPropertyOrder({"id", "boundaryType", "parentBoundaryType", "active"})
public class BoundaryTypeHierarchy {

    @JsonProperty("id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String id;

    @JsonProperty("boundaryType")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String boundaryType;

    // *string with no omitempty: always serialized, null kept as null.
    @JsonProperty("parentBoundaryType")
    private String parentBoundaryType;

    @JsonProperty("active")
    private boolean active;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBoundaryType() { return boundaryType; }
    public void setBoundaryType(String boundaryType) { this.boundaryType = boundaryType; }
    public String getParentBoundaryType() { return parentBoundaryType; }
    public void setParentBoundaryType(String parentBoundaryType) { this.parentBoundaryType = parentBoundaryType; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
