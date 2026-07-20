package com.digit.employee.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * API response for employee operations. Mirrors Go {@code EmployeeResponse}: {@code id} and
 * {@code isActive} always serialized; everything else {@code omitempty}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"id", "code", "userId", "individualId", "status", "employeeType",
        "dateOfAppointment", "department", "designation", "isActive", "version", "jurisdictions", "auditDetail"})
public class EmployeeResponse {

    private String id;
    private String code;
    private String userId;
    private String individualId;
    private String status;
    private String employeeType;
    private OffsetDateTime dateOfAppointment;
    private String department;
    private String designation;
    private boolean isActive;
    private int version;
    private List<JurisdictionResponse> jurisdictions;
    private AuditDetails auditDetail;

    @JsonInclude(JsonInclude.Include.ALWAYS)
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

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @com.fasterxml.jackson.annotation.JsonProperty("isActive")
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public List<JurisdictionResponse> getJurisdictions() { return jurisdictions; }
    public void setJurisdictions(List<JurisdictionResponse> jurisdictions) { this.jurisdictions = jurisdictions; }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public AuditDetails getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetails auditDetail) { this.auditDetail = auditDetail; }
}
