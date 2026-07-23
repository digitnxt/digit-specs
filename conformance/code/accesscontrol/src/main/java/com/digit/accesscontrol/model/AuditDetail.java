package com.digit.accesscontrol.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AuditDetail captures who/when for create + most recent modification of a row.
 * Mirrors Go internal/model/audit.go: JSON keys createdBy/createdTime/modifiedBy/modifiedTime,
 * DB columns created_by/created_at/modified_by/updated_at.
 */
public class AuditDetail {

    @JsonProperty("createdBy")
    private String createdBy = "";
    @JsonProperty("createdTime")
    private long createdTime;
    @JsonProperty("modifiedBy")
    private String modifiedBy = "";
    @JsonProperty("modifiedTime")
    private long modifiedTime;

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
}
