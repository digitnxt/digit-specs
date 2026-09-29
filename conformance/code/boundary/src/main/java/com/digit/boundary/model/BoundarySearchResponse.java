package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Hierarchical relationship search response. Mirrors Go models.BoundarySearchResponse. */
public class BoundarySearchResponse {

    @JsonProperty("tenantBoundary")
    private List<HierarchyRelation> tenantBoundary;

    public BoundarySearchResponse() {}

    public BoundarySearchResponse(List<HierarchyRelation> tenantBoundary) {
        this.tenantBoundary = tenantBoundary;
    }

    public List<HierarchyRelation> getTenantBoundary() { return tenantBoundary; }
    public void setTenantBoundary(List<HierarchyRelation> tenantBoundary) { this.tenantBoundary = tenantBoundary; }
}
