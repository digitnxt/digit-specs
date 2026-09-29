package com.digit.employee.model;

import java.util.List;

/**
 * The Keycloak-user slice of an onboarding request. Mirrors Go {@code OnboardUser}. mobileNumber
 * becomes the username (immutable) and is required; password is required (no server-side generation).
 * roles are realm roles, validated to exist before the user is created.
 */
public class OnboardUser {

    private String mobileNumber;
    private String password;
    private String email;
    private String firstName;
    private String lastName;
    private boolean emailVerified;
    private List<String> roles;

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}