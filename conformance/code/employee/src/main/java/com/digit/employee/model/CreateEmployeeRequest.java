package com.digit.employee.model;

import java.time.OffsetDateTime;
import java.util.List;

/** Request payload for creating/replacing an employee. Mirrors Go {@code CreateEmployeeRequest}. */
public class CreateEmployeeRequest {

    private String code;
    private String userId;
    private String individualId;
    private String status;
    private String employeeType;
    private OffsetDateTime dateOfAppointment;
    private String department;
    private String designation;
    private Boolean isActive;
    private List<Jurisdiction> jurisdictions;

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
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public List<Jurisdiction> getJurisdictions() { return jurisdictions; }
    public void setJurisdictions(List<Jurisdiction> jurisdictions) { this.jurisdictions = jurisdictions; }
}
