package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Boundary relationship entity. Mirrors Go models.BoundaryRelationship (table boundary_relationship_v1).
 */
@JsonPropertyOrder({"id", "tenantId", "code", "hierarchyType", "boundaryType", "parent",
        "ancestralMaterializedPath", "requestId", "auditDetails"})
public class BoundaryRelationship {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("tenantId")
    private String tenantId = "";

    @JsonProperty("code")
    private String code = "";

    @JsonProperty("hierarchyType")
    private String hierarchyType = "";

    @JsonProperty("boundaryType")
    private String boundaryType = "";

    @JsonProperty("parent")
    private String parent = "";

    @JsonProperty("ancestralMaterializedPath")
    private String ancestralMaterializedPath = "";

    @JsonProperty("requestId")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String requestId;

    @JsonProperty("auditDetails")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AuditDetails auditDetails;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getHierarchyType() { return hierarchyType; }
    public void setHierarchyType(String hierarchyType) { this.hierarchyType = hierarchyType; }
    public String getBoundaryType() { return boundaryType; }
    public void setBoundaryType(String boundaryType) { this.boundaryType = boundaryType; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public String getAncestralMaterializedPath() { return ancestralMaterializedPath; }
    public void setAncestralMaterializedPath(String ancestralMaterializedPath) { this.ancestralMaterializedPath = ancestralMaterializedPath; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetails getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetails auditDetails) { this.auditDetails = auditDetails; }
}
