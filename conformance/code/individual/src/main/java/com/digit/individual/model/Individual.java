package com.digit.individual.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DB-layer entity for an individual. Mirrors Go internal/models/individual_entity.go.
 * Table individual_v3. additionaldetails is a raw jsonb map.
 */
public class Individual {
    private String id;
    private String individualId;
    private String tenantId;
    private String givenName;
    private String familyName;
    private String otherNames;
    private LocalDate dateOfBirth;
    private String gender;
    private Integer age;
    private String mobileNumber;
    private String hashedMobileNumber;
    private boolean mobileNumberVerified;
    private String altContactNumber;
    private String email;
    private boolean emailVerified;
    private String locale;
    private boolean active;
    private String fatherName;
    private String husbandName;
    private String photo;
    private String userId;
    private Map<String, Object> additionalDetails;
    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;
    private int rowVersion;
    private String requestId;

    private List<Address> addresses = new ArrayList<>();
    private List<Identifier> identifiers = new ArrayList<>();
    private List<Document> documents = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIndividualId() { return individualId; }
    public void setIndividualId(String individualId) { this.individualId = individualId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }
    public String getOtherNames() { return otherNames; }
    public void setOtherNames(String otherNames) { this.otherNames = otherNames; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getHashedMobileNumber() { return hashedMobileNumber; }
    public void setHashedMobileNumber(String hashedMobileNumber) { this.hashedMobileNumber = hashedMobileNumber; }
    public boolean isMobileNumberVerified() { return mobileNumberVerified; }
    public void setMobileNumberVerified(boolean mobileNumberVerified) { this.mobileNumberVerified = mobileNumberVerified; }
    public String getAltContactNumber() { return altContactNumber; }
    public void setAltContactNumber(String altContactNumber) { this.altContactNumber = altContactNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getFatherName() { return fatherName; }
    public void setFatherName(String fatherName) { this.fatherName = fatherName; }
    public String getHusbandName() { return husbandName; }
    public void setHusbandName(String husbandName) { this.husbandName = husbandName; }
    public String getPhoto() { return photo; }
    public void setPhoto(String photo) { this.photo = photo; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Map<String, Object> getAdditionalDetails() { return additionalDetails; }
    public void setAdditionalDetails(Map<String, Object> additionalDetails) { this.additionalDetails = additionalDetails; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
    public int getRowVersion() { return rowVersion; }
    public void setRowVersion(int rowVersion) { this.rowVersion = rowVersion; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    // Child collections are never null: a null setter argument (e.g. a request body with
    // "addresses": null) coalesces to an empty list, so callers can iterate without guards —
    // matching Go, where ranging over a nil slice is a safe no-op rather than a panic.
    public List<Address> getAddresses() { return addresses; }
    public void setAddresses(List<Address> addresses) { this.addresses = addresses == null ? new ArrayList<>() : addresses; }
    public List<Identifier> getIdentifiers() { return identifiers; }
    public void setIdentifiers(List<Identifier> identifiers) { this.identifiers = identifiers == null ? new ArrayList<>() : identifiers; }
    public List<Document> getDocuments() { return documents; }
    public void setDocuments(List<Document> documents) { this.documents = documents == null ? new ArrayList<>() : documents; }
}
