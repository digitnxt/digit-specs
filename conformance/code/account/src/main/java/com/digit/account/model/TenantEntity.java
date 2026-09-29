package com.digit.account.model;

import java.util.List;
import java.util.Map;

/**
 * DB-layer representation of a tenant row in {@code tenant_v1}. Mirrors Go models/tenant_entity.go.
 * Nullable contact fields are {@code String} (null = SQL NULL); additionalattributes is jsonb.
 */
public class TenantEntity {
    private String id;
    private String code;
    private String name;
    private String email;
    private String phone;          // nullable
    private String address;        // nullable
    private String city;           // nullable
    private String state;          // nullable
    private String pincode;        // nullable
    private String country;        // nullable
    // The sign-in link this tenant's admin was emailed. Server-set, never accepted from a caller.
    // Ordered; first entry is the primary link. Null/empty when nothing was emailed.
    private Map<String, String> firstLoginUrls;
    private Map<String, Object> additionalAttributes; // jsonb
    private boolean isActive;
    private boolean passwordGenerated;
    private int version;
    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;
    private String requestId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
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
    public Map<String, String> getFirstLoginUrls() { return firstLoginUrls; }
    public void setFirstLoginUrls(Map<String, String> v) { this.firstLoginUrls = v; }
    public Map<String, Object> getAdditionalAttributes() { return additionalAttributes; }
    public void setAdditionalAttributes(Map<String, Object> v) { this.additionalAttributes = v; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public boolean isPasswordGenerated() { return passwordGenerated; }
    public void setPasswordGenerated(boolean v) { this.passwordGenerated = v; }
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
