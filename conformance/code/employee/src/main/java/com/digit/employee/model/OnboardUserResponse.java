package com.digit.employee.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The user slice of an onboarding response. Returns only the server-decided facts: the new id, the
 * derived username (= mobileNumber, surfaced so the caller knows their login identifier without
 * re-deriving our rule), and the roles actually assigned. Everything else the client already sent, so
 * it is not echoed; the password is never returned. Mirrors Go {@code OnboardUserResponse}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class OnboardUserResponse {

    private String id;
    private String username;
    private List<String> roles;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}