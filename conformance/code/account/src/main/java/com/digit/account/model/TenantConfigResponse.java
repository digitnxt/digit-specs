package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Wire-layer config response. Mirrors Go models.TenantConfigResponse field order + omitempty. */
@JsonPropertyOrder({"id", "tenantId", "configKey", "configValue", "description", "isActive",
        "version", "auditDetail"})
public class TenantConfigResponse {
    private String id;
    private String tenantId;
    private String configKey;
    private String configValue;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String description;
    private boolean isActive;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int version;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AuditDetail auditDetail;

    @JsonProperty("isActive")
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

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
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
}
