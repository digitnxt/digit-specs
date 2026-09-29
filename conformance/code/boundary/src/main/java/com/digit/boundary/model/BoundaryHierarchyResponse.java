package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Response containing boundary hierarchies. Mirrors Go models.BoundaryHierarchyResponse. */
public class BoundaryHierarchyResponse {

    @JsonProperty("hierarchy")
    private List<BoundaryHierarchy> hierarchy;

    public BoundaryHierarchyResponse() {}

    public BoundaryHierarchyResponse(List<BoundaryHierarchy> hierarchy) {
        this.hierarchy = hierarchy;
    }

    public List<BoundaryHierarchy> getHierarchy() { return hierarchy; }
    public void setHierarchy(List<BoundaryHierarchy> hierarchy) { this.hierarchy = hierarchy; }
}
