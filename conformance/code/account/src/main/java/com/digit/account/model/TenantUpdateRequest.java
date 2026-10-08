package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Wire-layer payload for {@code PUT /tenants/{id}}. Mirrors Go models.TenantUpdateRequest.
 * Each optional field follows "omit to retain" — a null value leaves the column unchanged.
 */
public class TenantUpdateRequest {
    private String phone;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String country;
    // Boxed so "omit to retain" still works: only a present true/false flips the flag, and
    // flipping it enables/disables the tenant's Keycloak realm.
    private Boolean isActive;
    private Map<String, Object> additionalAttributes;
    // The version the client last read. Optional; when sent, the update applies only if the row still has it.
    private Integer version;

    @JsonProperty("isActive")
    public Boolean getIsActive() { return isActive; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    @JsonProperty("isActive")
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public Map<String, Object> getAdditionalAttributes() { return additionalAttributes; }
    public void setAdditionalAttributes(Map<String, Object> v) { this.additionalAttributes = v; }
}
