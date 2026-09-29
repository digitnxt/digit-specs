package com.digit.employee.model;

import java.util.Map;

/**
 * Combined response for onboarding: all three created artifacts. The individual is echoed as the raw
 * response body from the individual service, held as a {@code Map} so it serializes as a plain JSON
 * object regardless of the response converter's Jackson version. Mirrors Go {@code OnboardResponse}.
 */
public class OnboardResponse {

    private OnboardUserResponse user;
    private Map<String, Object> individual;
    private EmployeeResponse employee;

    public OnboardUserResponse getUser() { return user; }
    public void setUser(OnboardUserResponse user) { this.user = user; }
    public Map<String, Object> getIndividual() { return individual; }
    public void setIndividual(Map<String, Object> individual) { this.individual = individual; }
    public EmployeeResponse getEmployee() { return employee; }
    public void setEmployee(EmployeeResponse employee) { this.employee = employee; }
}