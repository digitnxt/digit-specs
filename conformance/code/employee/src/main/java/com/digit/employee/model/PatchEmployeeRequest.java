package com.digit.employee.model;

import java.util.List;

/**
 * PATCH body — partial update (mirrors Go {@code PatchEmployeeRequest}). Every field is nullable so
 * "omitted" is distinguishable from "explicit zero value": omitted fields preserve the existing DB
 * value, supplied fields overwrite. Mutable surface: status, employeeType, department, designation,
 * isActive, jurisdictions. jurisdictions has reconcile-on-set semantics (id+version → update in place,
 * id-less → insert, omitted → deactivate; empty list deactivates all, null leaves untouched). At least
 * one field must be supplied ({@link #hasAnyField()} → 400 otherwise). version is required.
 */
public class PatchEmployeeRequest {

    private String status;
    private String employeeType;
    private String department;
    private String designation;
    private Boolean isActive;
    private List<Jurisdiction> jurisdictions;
    // Optimistic-concurrency token, required even on a partial update (the client must prove it saw
    // the current employee state). Not counted by {@link #hasAnyField()} — it is the guard, not a
    // mutable field.
    private Integer version;

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
    public List<Jurisdiction> getJurisdictions() { return jurisdictions; }
    public void setJurisdictions(List<Jurisdiction> jurisdictions) { this.jurisdictions = jurisdictions; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    /** True when at least one mutable field is supplied; an empty {@code {}} body → false → 400. */
    public boolean hasAnyField() {
        return status != null || employeeType != null || department != null
                || designation != null || isActive != null || jurisdictions != null;
    }
}
