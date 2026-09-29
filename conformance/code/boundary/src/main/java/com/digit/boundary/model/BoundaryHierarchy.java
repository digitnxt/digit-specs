package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Boundary hierarchy definition. Mirrors Go models.BoundaryHierarchyDefinition (= BoundaryHierarchy),
 * table boundary_hierarchy_v1. {@code boundaryHierarchy} is the jsonb list of levels.
 */
@JsonPropertyOrder({"id", "tenantId", "hierarchyType", "boundaryHierarchy", "requestId", "auditDetails"})
public class BoundaryHierarchy {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("tenantId")
    private String tenantId = "";

    @JsonProperty("hierarchyType")
    private String hierarchyType = "";

    // Go BoundaryHierarchyList zero value is nil (serialized as JSON null, no omitempty),
    // so no []-default here: an unset list must render as null, not [].
    @JsonProperty("boundaryHierarchy")
    private List<BoundaryTypeHierarchy> boundaryHierarchy;

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
    public String getHierarchyType() { return hierarchyType; }
    public void setHierarchyType(String hierarchyType) { this.hierarchyType = hierarchyType; }
    public List<BoundaryTypeHierarchy> getBoundaryHierarchy() { return boundaryHierarchy; }
    public void setBoundaryHierarchy(List<BoundaryTypeHierarchy> boundaryHierarchy) { this.boundaryHierarchy = boundaryHierarchy; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetails getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetails auditDetails) { this.auditDetails = auditDetails; }
}
