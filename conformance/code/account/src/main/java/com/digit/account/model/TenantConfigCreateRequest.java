package com.digit.account.model;

/**
 * Wire-layer payload for {@code POST /config}. Mirrors Go models.TenantConfigCreateRequest.
 *
 * <p>{@code tenantId} is not part of the body — the endpoint is tenant-scoped and resolves the
 * owning tenant from the X-Tenant-Id header, so carrying it twice would only create a mismatch
 * to reconcile.
 */
public class TenantConfigCreateRequest {
    private String configKey;
    private String configValue;
    private String description;

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
