package com.digit.employee.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Combined payload for {@code POST /employee/v3/employees/onboard}. In one call it provisions a
 * Keycloak user, an individual, and an employee. Mirrors Go {@code OnboardRequest}.
 *
 * Each slice is validated by its owner: {@code user} in the service (Keycloak has no domain
 * validator), {@code individual} by the individual service, {@code employee} by the employee create
 * path. Injected/derived fields (tenantId, the new userId, the new individualId) are set server-side.
 * {@code individual} is kept as raw {@link JsonNode} so the individual DTO is not re-modelled here and
 * its own validation stays the single source of truth.
 */
public class OnboardRequest {

    private OnboardUser user;
    private JsonNode individual;
    private CreateEmployeeRequest employee;

    public OnboardUser getUser() { return user; }
    public void setUser(OnboardUser user) { this.user = user; }
    public JsonNode getIndividual() { return individual; }
    public void setIndividual(JsonNode individual) { this.individual = individual; }
    public CreateEmployeeRequest getEmployee() { return employee; }
    public void setEmployee(CreateEmployeeRequest employee) { this.employee = employee; }
}