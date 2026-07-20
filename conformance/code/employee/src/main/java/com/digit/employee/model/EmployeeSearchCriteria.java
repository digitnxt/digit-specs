package com.digit.employee.model;

import java.util.List;

/**
 * Search criteria for employees. Mirrors Go {@code EmployeeSearchCriteria} (post-8749c30e):
 * multi-value IN filters, a fixed server-side sort, and role-based search resolved via Keycloak.
 * {@code userIds} is not client-bindable — the service populates it after resolving {@code role}.
 */
public class EmployeeSearchCriteria {

    private List<String> ids;
    private List<String> codes;
    private List<String> statuses;
    private List<String> employeeTypes;
    private List<String> departments;
    private List<String> designations;
    private String dateOfAppointmentFrom; // yyyy-MM-dd
    private String dateOfAppointmentTo;   // yyyy-MM-dd
    private Boolean isActive;
    /** Keycloak realm role; resolved to userIds by the service, never queried directly. */
    private String role;
    /** Populated internally from role resolution; when non-empty the repo adds user_id IN (...). */
    private List<String> userIds;
    private int limit = 10;
    private int offset = 0;
    private String tenantId;

    public List<String> getIds() { return ids; }
    public void setIds(List<String> ids) { this.ids = ids; }
    public List<String> getCodes() { return codes; }
    public void setCodes(List<String> codes) { this.codes = codes; }
    public List<String> getStatuses() { return statuses; }
    public void setStatuses(List<String> statuses) { this.statuses = statuses; }
    public List<String> getEmployeeTypes() { return employeeTypes; }
    public void setEmployeeTypes(List<String> employeeTypes) { this.employeeTypes = employeeTypes; }
    public List<String> getDepartments() { return departments; }
    public void setDepartments(List<String> departments) { this.departments = departments; }
    public List<String> getDesignations() { return designations; }
    public void setDesignations(List<String> designations) { this.designations = designations; }
    public String getDateOfAppointmentFrom() { return dateOfAppointmentFrom; }
    public void setDateOfAppointmentFrom(String v) { this.dateOfAppointmentFrom = v; }
    public String getDateOfAppointmentTo() { return dateOfAppointmentTo; }
    public void setDateOfAppointmentTo(String v) { this.dateOfAppointmentTo = v; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public List<String> getUserIds() { return userIds; }
    public void setUserIds(List<String> userIds) { this.userIds = userIds; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
