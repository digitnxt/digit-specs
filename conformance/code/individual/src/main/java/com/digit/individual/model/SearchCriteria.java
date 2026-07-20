package com.digit.individual.model;

import java.util.List;

/**
 * Internal search parameters used by service/repo layers. Mirrors Go
 * internal/models/request_response.go SearchCriteria (subset actually exercised by the
 * query/exists handlers; remaining fields preserved for parity).
 */
public class SearchCriteria {
    private List<String> id;
    private List<String> individualId;
    private String givenName;
    private List<String> mobileNumber;
    private String gender;
    private String dateOfBirth;
    private List<String> userId;
    private List<String> userUuid;
    private List<String> username;
    private Long createdFrom;
    private Long createdTo;

    public List<String> getId() { return id; }
    public void setId(List<String> id) { this.id = id; }
    public List<String> getIndividualId() { return individualId; }
    public void setIndividualId(List<String> individualId) { this.individualId = individualId; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }
    public List<String> getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(List<String> mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public List<String> getUserId() { return userId; }
    public void setUserId(List<String> userId) { this.userId = userId; }
    public List<String> getUserUuid() { return userUuid; }
    public void setUserUuid(List<String> userUuid) { this.userUuid = userUuid; }
    public List<String> getUsername() { return username; }
    public void setUsername(List<String> username) { this.username = username; }
    public Long getCreatedFrom() { return createdFrom; }
    public void setCreatedFrom(Long createdFrom) { this.createdFrom = createdFrom; }
    public Long getCreatedTo() { return createdTo; }
    public void setCreatedTo(Long createdTo) { this.createdTo = createdTo; }
}
