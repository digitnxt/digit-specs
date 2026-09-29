package com.digit.boundary.model;

import java.util.List;

/**
 * Search criteria for boundary relationships. Mirrors Go models.BoundaryRelationshipSearchCriteria,
 * including the internal-only fields {@code currentBoundaryCodes} and {@code isSearchForRootNode}.
 */
public class BoundaryRelationshipSearchCriteria {

    private String tenantId = "";
    private String hierarchyType = "";
    private String boundaryType = "";
    private List<String> codes;
    private String parent = "";
    private boolean includeChildren;
    private boolean includeParents;
    private int limit;
    private int offset;
    // Geo search: when both are set, the point is first resolved to the boundary
    // codes containing it (PostGIS ST_Contains on boundary_v1.geom), which then
    // drive the relationship search. Null when not a geo search.
    private Double latitude;
    private Double longitude;

    // Internal-only (not exposed in JSON / query).
    private List<String> currentBoundaryCodes;
    private boolean searchForRootNode;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getHierarchyType() { return hierarchyType; }
    public void setHierarchyType(String hierarchyType) { this.hierarchyType = hierarchyType; }
    public String getBoundaryType() { return boundaryType; }
    public void setBoundaryType(String boundaryType) { this.boundaryType = boundaryType; }
    public List<String> getCodes() { return codes; }
    public void setCodes(List<String> codes) { this.codes = codes; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public boolean isIncludeChildren() { return includeChildren; }
    public void setIncludeChildren(boolean includeChildren) { this.includeChildren = includeChildren; }
    public boolean isIncludeParents() { return includeParents; }
    public void setIncludeParents(boolean includeParents) { this.includeParents = includeParents; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public List<String> getCurrentBoundaryCodes() { return currentBoundaryCodes; }
    public void setCurrentBoundaryCodes(List<String> currentBoundaryCodes) { this.currentBoundaryCodes = currentBoundaryCodes; }
    public boolean isSearchForRootNode() { return searchForRootNode; }
    public void setSearchForRootNode(boolean searchForRootNode) { this.searchForRootNode = searchForRootNode; }
}
