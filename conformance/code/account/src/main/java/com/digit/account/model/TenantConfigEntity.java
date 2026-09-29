package com.digit.account.model;

/**
 * DB-layer representation of a tenant config row in {@code tenant_config_v1} (v3 key/value shape).
 * Mirrors Go models/tenant_config_entity.go.
 */
public class TenantConfigEntity {
    private String id;
    /**
     * The tenant <em>code</em> (tenant_v1.code), not the tenant row's UUID — the platform
     * identifies tenants by code everywhere the X-Tenant-Id header travels, so the config
     * scope key follows suit.
     */
    private String tenantId;
    private String configKey;
    private String configValue;
    private String description;
    private boolean isActive;
    private int version;
    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;
    private String requestId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
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
