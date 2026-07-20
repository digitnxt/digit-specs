package com.digit.employee.model;

import java.util.List;

/**
 * PUT body — a strict full-state declaration (mirrors Go {@code UpdateEmployeeRequest}). Every mutable
 * field is required; immutable fields (id, code, userId, individualId, dateOfAppointment, tenantId,
 * audit) are intentionally absent and cannot be changed via PUT. Use PATCH for partial updates.
 */
public class UpdateEmployeeRequest {

    private String employeeType;
    private String department;
    private String designation;
    private String status;
    private Boolean isActive;
    private List<Jurisdiction> jurisdictions;
    // Optimistic-concurrency token the client last read. Required (validated in the service); the
    // update compare-and-swaps on it → 409 ROW_VERSION_MISMATCH on staleness. Jurisdiction items
    // carry their own id+version for the in-place reconcile.
    private Integer version;

    public String getEmployeeType() { return employeeType; }
    public void setEmployeeType(String employeeType) { this.employeeType = employeeType; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public List<Jurisdiction> getJurisdictions() { return jurisdictions; }
    public void setJurisdictions(List<Jurisdiction> jurisdictions) { this.jurisdictions = jurisdictions; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
