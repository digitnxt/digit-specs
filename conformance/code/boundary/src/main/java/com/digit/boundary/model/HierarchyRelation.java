package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Root(s) of a boundary tree for a tenant/hierarchy. Mirrors Go models.HierarchyRelation. */
@JsonPropertyOrder({"tenantId", "hierarchyType", "boundary"})
public class HierarchyRelation {

    @JsonProperty("tenantId")
    private String tenantId = "";

    @JsonProperty("hierarchyType")
    private String hierarchyType = "";

    @JsonProperty("boundary")
    private List<EnrichedBoundary> boundary;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getHierarchyType() { return hierarchyType; }
    public void setHierarchyType(String hierarchyType) { this.hierarchyType = hierarchyType; }
    public List<EnrichedBoundary> getBoundary() { return boundary; }
    public void setBoundary(List<EnrichedBoundary> boundary) { this.boundary = boundary; }
}
