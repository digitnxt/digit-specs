package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Audit information for entities. Mirrors Go internal/common/models.AuditDetails:
 * string createdBy/modifiedBy, int64 createdTime/modifiedTime, all with {@code omitempty}.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class AuditDetails {

    // NON_EMPTY (overrides the class-level NON_DEFAULT) so an empty string is omitted, matching Go's
    // string `omitempty` (which drops ""); the long fields keep NON_DEFAULT so 0 is omitted like Go.
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String createdBy;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
}
