package com.digit.employee.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Pairs a boundary code with the hierarchyType and boundaryType it belongs to. Mirrors Go
 * internal/models/jurisdiction.go {@code BoundaryRef} (field order: code, boundaryType, hierarchyType).
 */
@JsonPropertyOrder({"code", "boundaryType", "hierarchyType"})
public class BoundaryRef {

    private String code;
    private String boundaryType;
    private String hierarchyType;

    public BoundaryRef() {}

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getBoundaryType() { return boundaryType; }
    public void setBoundaryType(String boundaryType) { this.boundaryType = boundaryType; }
    public String getHierarchyType() { return hierarchyType; }
    public void setHierarchyType(String hierarchyType) { this.hierarchyType = hierarchyType; }
}
