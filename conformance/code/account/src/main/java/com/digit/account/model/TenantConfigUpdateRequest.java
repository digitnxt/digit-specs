package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire-layer payload for {@code PUT /config/{id}}. Every field follows "omit to retain", so a patch
 * may carry just the one field it means to change.
 *
 * <p>{@code description} needs {@link #descriptionPresent} rather than a null check because it is
 * the only nullable column here: an explicit null has to be distinguishable from an absent field so
 * a description can actually be cleared. {@code configKey} and {@code configValue} are NOT NULL, so
 * for them a null is only ever "absent" and needs no such tracking.
 */
public class TenantConfigUpdateRequest {
    private String configKey;
    private String configValue;
    private String description;
    private boolean descriptionPresent;
    // Boxed rather than presence-tracked like description: null is already unambiguous for a
    // Boolean, so absent means retain and only a literal true/false flips the flag.
    private Boolean isActive;

    @JsonProperty("isActive")
    public Boolean getIsActive() { return isActive; }
    @JsonProperty("isActive")
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }
    public boolean isDescriptionPresent() { return descriptionPresent; }
}
