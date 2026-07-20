package com.digit.individual.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Wire-layer audit info nested under {@code auditDetail}. Mirrors Go internal/models/audit.go.
 * readOnly: set only by entity→DTO mapping; each field is omitempty (omitted when zero/empty).
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({"createdBy", "createdTime", "modifiedBy", "modifiedTime"})
public class AuditDetail {
    private String createdBy;
    private long createdTime;
    private String modifiedBy;
    private long modifiedTime;

    public AuditDetail() {}

    public AuditDetail(String createdBy, String modifiedBy, long createdTime, long modifiedTime) {
        this.createdBy = createdBy;
        this.modifiedBy = modifiedBy;
        this.createdTime = createdTime;
        this.modifiedTime = modifiedTime;
    }

    /** Returns a populated AuditDetail, or null if every flat audit value is zero (matches Go newAuditDetail). */
    public static AuditDetail of(String createdBy, String modifiedBy, long createdTime, long modifiedTime) {
        if ((createdBy == null || createdBy.isEmpty()) && (modifiedBy == null || modifiedBy.isEmpty())
                && createdTime == 0 && modifiedTime == 0) {
            return null;
        }
        return new AuditDetail(createdBy, modifiedBy, createdTime, modifiedTime);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
}
