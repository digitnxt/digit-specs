package com.digit.employee.service;

import com.digit.employee.client.IndividualClient;
import com.digit.employee.client.KeycloakApiException;
import com.digit.employee.client.KeycloakClient;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.OnboardRequest;
import com.digit.employee.model.OnboardUser;
import com.digit.employee.observability.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** How onboarding reports Keycloak failures on the calls made with the caller's token. */
class OnboardKeycloakErrorTest {

    private final ObjectMapper om = new ObjectMapper();
    private KeycloakClient keycloak;
    private IndividualClient individual;
    private EmployeeService svc;

    @BeforeEach
    void setUp() {
        keycloak = Mockito.mock(KeycloakClient.class);
        individual = Mockito.mock(IndividualClient.class);
        EmployeeProperties props = new EmployeeProperties();
        props.getKeycloak().setEnabled(true);
        svc = new EmployeeService(null, null, null, individual, keycloak, props, null,
                Mockito.mock(BusinessMetrics.class), TransactionOperations.withoutTransaction());
        Mockito.when(keycloak.getRealmRole(anyString(), anyString(), any()))
                .thenReturn(om.createObjectNode().put("name", "EMPLOYEE"));
    }

    private OnboardRequest request(List<String> roles) {
        OnboardUser user = new OnboardUser();
        user.setMobileNumber("9999999999");
        user.setPassword("secret");
        user.setRoles(roles);
        OnboardRequest req = new OnboardRequest();
        req.setUser(user);
        req.setIndividual(om.createObjectNode().put("givenName", "A"));
        req.setEmployee(new CreateEmployeeRequest());
        return req;
    }

    private CustomException createUserFailsWith(RuntimeException e) {
        Mockito.when(keycloak.createUser(anyString(), any(), any())).thenThrow(e);
        CustomException ex = assertThrows(CustomException.class,
                () -> svc.onboardEmployee(request(List.of()), "t1", "Bearer x", "u1"));
        Mockito.verifyNoInteractions(individual);
        return ex;
    }

    private CustomException assignRolesFailsWith(RuntimeException e) {
        Mockito.when(keycloak.createUser(anyString(), any(), any())).thenReturn("kc-1");
        Mockito.doThrow(e).when(keycloak).assignRealmRoles(any(), any(), any(), any());
        CustomException ex = assertThrows(CustomException.class,
                () -> svc.onboardEmployee(request(List.of("EMPLOYEE")), "t1", "Bearer x", "u1"));
        Mockito.verify(keycloak).deleteUser("t1", "kc-1", "Bearer x");
        Mockito.verifyNoInteractions(individual);
        return ex;
    }

    @Test
    void createUser_forbidden_is403() {
        CustomException ex = createUserFailsWith(new KeycloakApiException(403, "{\"error\":\"HTTP 403 Forbidden\"}"));
        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        assertEquals("not permitted to create users in this tenant", ex.getMessage());
    }

    @Test
    void createUser_badRequest_is400WithKeycloakReason() {
        CustomException ex = createUserFailsWith(new KeycloakApiException(400,
                "{\"field\":\"email\",\"errorMessage\":\"error-invalid-email\",\"params\":[\"email\",\"x\"]}"));
        assertEquals("INVALID_REQUEST", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("failed to create user in keycloak: email: error-invalid-email", ex.getMessage());
    }

    @Test
    void createUser_unauthorized_is401() {
        CustomException ex = createUserFailsWith(new KeycloakApiException(401, ""));
        assertEquals("UNAUTHORIZED", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getHttpStatus());
    }

    @Test
    void createUser_conflict_keepsConflictMessage() {
        CustomException ex = createUserFailsWith(new KeycloakApiException(409, "{\"errorMessage\":\"User exists with same username\"}"));
        assertEquals("CONFLICT", ex.getCode());
        assertEquals("a user with this mobile number or email already exists", ex.getMessage());
    }

    @Test
    void createUser_serverError_is502() {
        CustomException ex = createUserFailsWith(new KeycloakApiException(500, "boom"));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("failed to create user in keycloak", ex.getMessage());
    }

    @Test
    void createUser_transportFailure_is502() {
        CustomException ex = createUserFailsWith(new RuntimeException("keycloak service request failed: timeout"));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
    }

    @Test
    void assignRoles_forbidden_is403AndUserDeleted() {
        CustomException ex = assignRolesFailsWith(new KeycloakApiException(403, ""));
        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals("not permitted to assign one of the requested roles", ex.getMessage());
    }

    @Test
    void assignRoles_badRequest_is400AndUserDeleted() {
        CustomException ex = assignRolesFailsWith(new KeycloakApiException(400, "{\"errorMessage\":\"invalid role\"}"));
        assertEquals("INVALID_REQUEST", ex.getCode());
        assertEquals("failed to assign roles to user: invalid role", ex.getMessage());
    }

    @Test
    void assignRoles_serverError_is502AndUserDeleted() {
        CustomException ex = assignRolesFailsWith(new KeycloakApiException(503, ""));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
    }
}
