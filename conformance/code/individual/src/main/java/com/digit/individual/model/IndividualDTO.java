package com.digit.individual.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * Wire-layer shape per the v3 spec. Mirrors Go internal/models/individual_dto.go.
 * Notes: tenantId sourced from header (absent here); rowVersion exposed as "version";
 * active exposed as "isActive"; additionaldetails exposed as "additionalAttributes";
 * dateOfBirth is a string (YYYY-MM-DD on output; flexible parse on input).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"id", "individualId", "givenName", "familyName", "otherNames", "dateOfBirth",
        "gender", "age", "mobileNumber", "mobileNumberVerified", "altContactNumber", "email",
        "emailVerified", "locale", "isActive", "fatherName", "husbandName", "photo", "userId",
        "additionalAttributes", "version", "requestId", "auditDetail", "address", "identifiers", "documents"})
public class IndividualDTO {

    private String id = "";
    private String individualId;
    private String givenName;
    private String familyName;
    private String otherNames;
    private String dateOfBirth;
    private String gender;
    private Integer age;
    private String mobileNumber;
    private boolean mobileNumberVerified;
    private String altContactNumber;
    private String email;
    private boolean emailVerified;
    private String locale;
    @JsonProperty("isActive")
    private boolean isActive;
    private String fatherName;
    private String husbandName;
    private String photo;
    private String userId;
    @JsonProperty("additionalAttributes")
    private Map<String, Object> additionalAttributes;
    private int version;
    private String requestId;
    private AuditDetail auditDetail;
    @JsonProperty("address")
    private List<AddressDTO> addresses;
    private List<IdentifierDTO> identifiers;
    private List<DocumentDTO> documents;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIndividualId() { return individualId; }
    public void setIndividualId(String individualId) { this.individualId = individualId; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }
    public String getOtherNames() { return otherNames; }
    public void setOtherNames(String otherNames) { this.otherNames = otherNames; }
    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isMobileNumberVerified() { return mobileNumberVerified; }
    public void setMobileNumberVerified(boolean mobileNumberVerified) { this.mobileNumberVerified = mobileNumberVerified; }
    public String getAltContactNumber() { return altContactNumber; }
    public void setAltContactNumber(String altContactNumber) { this.altContactNumber = altContactNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    @JsonProperty("isActive")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getFatherName() { return fatherName; }
    public void setFatherName(String fatherName) { this.fatherName = fatherName; }
    public String getHusbandName() { return husbandName; }
    public void setHusbandName(String husbandName) { this.husbandName = husbandName; }
    public String getPhoto() { return photo; }
    public void setPhoto(String photo) { this.photo = photo; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    @JsonProperty("additionalAttributes")
    public Map<String, Object> getAdditionalAttributes() { return additionalAttributes; }
    public void setAdditionalAttributes(Map<String, Object> additionalAttributes) { this.additionalAttributes = additionalAttributes; }
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
    @JsonProperty("address")
    public List<AddressDTO> getAddresses() { return addresses; }
    public void setAddresses(List<AddressDTO> addresses) { this.addresses = addresses; }
    public List<IdentifierDTO> getIdentifiers() { return identifiers; }
    public void setIdentifiers(List<IdentifierDTO> identifiers) { this.identifiers = identifiers; }
    public List<DocumentDTO> getDocuments() { return documents; }
    public void setDocuments(List<DocumentDTO> documents) { this.documents = documents; }
}
