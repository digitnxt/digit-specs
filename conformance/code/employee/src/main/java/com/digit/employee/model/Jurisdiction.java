package com.digit.employee.model;

import java.util.List;

/**
 * Jurisdiction entity (table {@code employee_jurisdiction_v3}). Mirrors Go
 * internal/models/jurisdiction.go {@code Jurisdiction}.
 */
public class Jurisdiction {

    private String id;
    private String employeeId;
    private List<BoundaryRef> boundaryRelation;
    private boolean isActive = true;
    private String tenantId;
    // Optimistic-concurrency token (column {@code version}), independent of the owning employee's
    // version. 1 on create, bumped on every mutation; updates compare-and-swap on it. When supplied
    // inside an employee PUT/PATCH body, carries the client's last-read version for the reconcile.
    private int version;
    private AuditDetails auditDetails = new AuditDetails();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public List<BoundaryRef> getBoundaryRelation() { return boundaryRelation; }
    public void setBoundaryRelation(List<BoundaryRef> boundaryRelation) { this.boundaryRelation = boundaryRelation; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public AuditDetails getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetails auditDetails) { this.auditDetails = auditDetails; }
}
