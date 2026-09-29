package com.digit.account.model;

import java.util.Map;

/** Wire-layer payload for {@code POST /tenants}. Mirrors Go models.TenantCreateRequest. */
public class TenantCreateRequest {
    private String name;
    private String email;
    private String password;
    private String phone;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String country;
    private Map<String, Object> additionalAttributes;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
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
