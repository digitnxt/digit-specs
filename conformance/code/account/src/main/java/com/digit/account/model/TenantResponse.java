package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/** Wire-layer tenant response. Mirrors Go models.TenantResponse field order + omitempty. */
@JsonPropertyOrder({"id", "code", "name", "email", "phone", "address", "city", "state", "pincode",
        "country", "isActive", "passwordGenerated", "temporaryPasswordEmailed", "firstLoginUrls",
        "additionalAttributes", "version", "auditDetail"})
public class TenantResponse {
    private String id;
    private String code;
    private String name;
    private String email;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String phone;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String address;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String city;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String state;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String pincode;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String country;
    // Where this tenant's admin signs in. Recorded at creation from the configured template, so a
    // later change to that template does not retro-edit tenants already provisioned.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> firstLoginUrls;
    private boolean isActive;
    private boolean passwordGenerated;
    // Whether the generated password actually reached the admin's inbox. Absent unless one was
    // generated: with a caller-supplied password there is nothing to deliver, and reporting false
    // would read as a delivery that failed. Never persisted — it describes one create call, not the
    // tenant, so reads and lists omit it.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean temporaryPasswordEmailed;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> additionalAttributes;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int version;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AuditDetail auditDetail;

    @JsonProperty("isActive")
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

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
    public boolean isPasswordGenerated() { return passwordGenerated; }
    public void setPasswordGenerated(boolean v) { this.passwordGenerated = v; }
    @JsonProperty("temporaryPasswordEmailed")
    public Boolean getTemporaryPasswordEmailed() { return temporaryPasswordEmailed; }
    public void setTemporaryPasswordEmailed(Boolean v) { this.temporaryPasswordEmailed = v; }
    public Map<String, Object> getAdditionalAttributes() { return additionalAttributes; }
    public void setAdditionalAttributes(Map<String, Object> v) { this.additionalAttributes = v; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
}
