package com.digit.employee.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Employee entity (table {@code employee_v3}). Mirrors Go internal/models/employee.go {@code Employee}.
 * Used internally between repository and service; the API response is {@link EmployeeResponse}.
 */
public class Employee {

    private String id;
    private String code;
    private String userId;
    private String individualId;
    private String status;
    private String employeeType;
    private OffsetDateTime dateOfAppointment;
    private String department;
    private String designation;
    private boolean isActive = true;
    private List<Jurisdiction> jurisdictions;
    private String tenantId;
    // Optimistic-concurrency token (column {@code version}). 1 on create, bumped on every mutation;
    // updates compare-and-swap on it.
    private int version = 1;
    private AuditDetails auditDetails = new AuditDetails();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIndividualId() { return individualId; }
    public void setIndividualId(String individualId) { this.individualId = individualId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEmployeeType() { return employeeType; }
    public void setEmployeeType(String employeeType) { this.employeeType = employeeType; }
    public OffsetDateTime getDateOfAppointment() { return dateOfAppointment; }
    public void setDateOfAppointment(OffsetDateTime dateOfAppointment) { this.dateOfAppointment = dateOfAppointment; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public List<Jurisdiction> getJurisdictions() { return jurisdictions; }
    public void setJurisdictions(List<Jurisdiction> jurisdictions) { this.jurisdictions = jurisdictions; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public AuditDetails getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetails auditDetails) { this.auditDetails = auditDetails; }
}
