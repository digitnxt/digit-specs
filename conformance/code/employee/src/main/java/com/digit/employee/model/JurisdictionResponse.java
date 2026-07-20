package com.digit.employee.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * API response for jurisdiction operations. Mirrors Go {@code JurisdictionResponse}: all fields
 * present (auditDetails is a struct value, so Go's {@code omitempty} on it has no effect; it always
 * serializes, with its own per-field omitempty).
 */
@JsonPropertyOrder({"id", "employeeId", "boundaryRelation", "isActive", "version", "auditDetail"})
public class JurisdictionResponse {

    private String id;
    private String employeeId;
    private List<BoundaryRef> boundaryRelation;
    private boolean isActive;
    private int version;
    // tenantId intentionally omitted — the caller already knows their tenant (matches Go).
    private AuditDetails auditDetail = new AuditDetails();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public List<BoundaryRef> getBoundaryRelation() { return boundaryRelation; }
    public void setBoundaryRelation(List<BoundaryRef> boundaryRelation) { this.boundaryRelation = boundaryRelation; }
    @com.fasterxml.jackson.annotation.JsonProperty("isActive")
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public AuditDetails getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetails auditDetail) { this.auditDetail = auditDetail; }
}
