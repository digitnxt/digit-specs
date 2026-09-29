package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Audit information nested under {@code auditDetail} in v3 responses. Mirrors Go models/audit.go;
 * zero values are omitted ({@code omitempty}).
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class AuditDetail {
    private String createdBy;
    private long createdTime;
    private String modifiedBy;
    private long modifiedTime;

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }

    /** Returns a populated AuditDetail, or null when every value is zero (Go newAuditDetail). */
    public static AuditDetail newAuditDetail(String createdBy, String modifiedBy,
                                             long createdTime, long modifiedTime) {
        boolean empty = (createdBy == null || createdBy.isEmpty())
                && (modifiedBy == null || modifiedBy.isEmpty())
                && createdTime == 0 && modifiedTime == 0;
        if (empty) {
            return null;
        }
        AuditDetail d = new AuditDetail();
        d.createdBy = createdBy;
        d.modifiedBy = modifiedBy;
        d.createdTime = createdTime;
        d.modifiedTime = modifiedTime;
        return d;
    }
}
