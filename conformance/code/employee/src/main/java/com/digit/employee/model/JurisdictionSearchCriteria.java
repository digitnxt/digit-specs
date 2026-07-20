package com.digit.employee.model;

import java.util.List;

/** Search criteria for jurisdictions. Mirrors Go {@code JurisdictionSearchCriteria}. */
public class JurisdictionSearchCriteria {

    // employeeId is intentionally absent — jurisdictions are nested under
    // /employees/{id}/jurisdictions, so the employee scope is passed positionally to
    // the service/repo, never as a query filter. Sort is fixed server-side (createdTime DESC).
    private List<String> ids;
    private Boolean isActive;
    private int limit = 10;
    private int offset = 0;
    private String tenantId;

    public List<String> getIds() { return ids; }
    public void setIds(List<String> ids) { this.ids = ids; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
