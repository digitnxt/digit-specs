package com.digit.employee.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Audit information for records. Mirrors Go internal/models/common.go {@code AuditDetails}, including
 * the JSON {@code omitempty} on all four fields (empty strings / zero longs are omitted).
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class AuditDetails {

    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;

    public AuditDetails() {}

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
}
