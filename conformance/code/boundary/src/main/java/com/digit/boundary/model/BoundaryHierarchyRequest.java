package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request to create or update a boundary hierarchy. Mirrors Go models.BoundaryHierarchyRequest. */
public class BoundaryHierarchyRequest {

    @JsonProperty("hierarchy")
    private BoundaryHierarchy hierarchy = new BoundaryHierarchy();

    public BoundaryHierarchy getHierarchy() { return hierarchy; }
    public void setHierarchy(BoundaryHierarchy hierarchy) { this.hierarchy = hierarchy; }
}
