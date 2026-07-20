package com.digit.individual.model;

/**
 * DB-layer entity for per-tenant validation config. Mirrors Go internal/models/config_entity.go
 * (table individual_config_v3). uniquenessCriteria is stored as raw jsonb (a JSON array string).
 */
public class Config {
    private long id;
    private String tenantId;
    private String mobileRegex;
    private String nameRegex;
    /** Raw JSON text for the jsonb uniquenesscriteria column (e.g. {@code ["mobileNumber","name"]}); null when absent. */
    private String uniquenessCriteria;
    private int version = 1;
    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;
    private String requestId;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMobileRegex() { return mobileRegex; }
    public void setMobileRegex(String mobileRegex) { this.mobileRegex = mobileRegex; }
    public String getNameRegex() { return nameRegex; }
    public void setNameRegex(String nameRegex) { this.nameRegex = nameRegex; }
    public String getUniquenessCriteria() { return uniquenessCriteria; }
    public void setUniquenessCriteria(String uniquenessCriteria) { this.uniquenessCriteria = uniquenessCriteria; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
