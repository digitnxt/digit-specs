package com.digit.boundary.model;

/** Search criteria for boundary hierarchies. Mirrors Go models.BoundaryHierarchySearchCriteria. */
public class BoundaryHierarchySearchCriteria {

    private String tenantId;
    private String hierarchyType;

    public BoundaryHierarchySearchCriteria() {}

    public BoundaryHierarchySearchCriteria(String tenantId, String hierarchyType) {
        this.tenantId = tenantId;
        this.hierarchyType = hierarchyType;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getHierarchyType() { return hierarchyType; }
    public void setHierarchyType(String hierarchyType) { this.hierarchyType = hierarchyType; }
}
