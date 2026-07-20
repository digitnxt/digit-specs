package com.digit.employee.model;

/**
 * Persistence change-set for PATCH (mirrors Go {@code EmployeePatch}). Null fields are skipped by the
 * repository's partial update; a non-null value (including {@code false}/empty) is written as-is.
 * Immutable attributes and jurisdictions are intentionally absent. Audit fields are server-set.
 */
public class EmployeePatch {

    private String status;
    private String employeeType;
    private String department;
    private String designation;
    private Boolean isActive;
    // Bumped optimistic-concurrency value (expected+1), written on every patch. The CAS predicate
    // (WHERE version = expected) is applied by the repository.
    private int version;
    private String modifiedBy;
    private long modifiedTime;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEmployeeType() { return employeeType; }
    public void setEmployeeType(String employeeType) { this.employeeType = employeeType; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
}
