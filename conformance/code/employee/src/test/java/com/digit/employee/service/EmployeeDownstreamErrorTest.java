package com.digit.employee.service;

import com.digit.employee.client.IdGenApiException;
import com.digit.employee.client.IdGenClient;
import com.digit.employee.client.IndividualApiException;
import com.digit.employee.client.IndividualClient;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/** How employee create reports failures of the services it depends on. */
class EmployeeDownstreamErrorTest {

    private final ObjectMapper om = new ObjectMapper();
    private IdGenClient idgen;
    private IndividualClient individual;
    private KeycloakClient keycloak;
    private EmployeeService svc;

    @BeforeEach
    void setUp() {
        idgen = Mockito.mock(IdGenClient.class);
        individual = Mockito.mock(IndividualClient.class);
        keycloak = Mockito.mock(KeycloakClient.class);
        EmployeeProperties props = new EmployeeProperties();
        props.getIdgen().setIdgenName("EmployeeCode");
        props.getIndividual().setEnabled(true);
        props.getKeycloak().setEnabled(true);
        svc = new EmployeeService(null, null, idgen, individual, keycloak, props, null,
                Mockito.mock(BusinessMetrics.class), TransactionOperations.withoutTransaction());
    }

    private static String digitError(String code, String message) {
        return "[{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}]";
    }

    private static CreateEmployeeRequest withoutCode() {
        CreateEmployeeRequest r = new CreateEmployeeRequest();
        r.setEmployeeType("PERMANENT");
        r.setDepartment("D");
        r.setDesignation("DE");
        return r;
    }

    private CustomException createFailsWhenIdgenThrows(RuntimeException e) {
        Mockito.when(idgen.generateIDs(anyString(), anyInt(), any())).thenThrow(e);
        return assertThrows(CustomException.class,
                () -> svc.createEmployees(List.of(withoutCode()), "P1", "Bearer x", "u1"));
    }

    @Test
    void idgenTemplateMissing_is500NamingTheTemplate() {
        CustomException ex = createFailsWhenIdgenThrows(
                new IdGenApiException(404, "[{\"code\":\"NOT_FOUND\",\"message\":\"template not found\"}]"));
        assertEquals("IDGEN_TEMPLATE_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getHttpStatus());
        assertEquals("failed to generate employee code: idgen template 'EmployeeCode' not found for tenant P1", ex.getMessage());
    }

    @Test
    void idgenUnprocessable_is502() {
        CustomException ex = createFailsWhenIdgenThrows(new IdGenApiException(422,
                "[{\"code\":\"UNPROCESSABLE\",\"message\":\"id generation failed: nextval failed: connection reset\"}]"));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("failed to generate employee code", ex.getMessage());
    }

    @Test
    void idgenUnreachable_is502() {
        CustomException ex = createFailsWhenIdgenThrows(new RuntimeException("failed to call IDGen service: timeout"));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
    }

    private CustomException createFailsWhenIndividualLookupThrows(RuntimeException e) {
        Mockito.when(individual.getIndividualByID(anyString(), anyString())).thenThrow(e);
        CreateEmployeeRequest r = withoutCode();
        r.setCode("EMP-1");
        r.setIndividualId("IND-0001");
        return assertThrows(CustomException.class,
                () -> svc.createEmployees(List.of(r), "P1", "Bearer x", "u1"));
    }

    @Test
    void individualIdRejected_is400WithReason() {
        CustomException ex = createFailsWhenIndividualLookupThrows(
                new IndividualApiException(400, digitError("VALIDATION_ERROR", "ID must be a valid UUID")));
        assertEquals("INVALID_REQUEST", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("invalid individualId: ID must be a valid UUID", ex.getMessage());
    }

    @Test
    void individualLookupServerError_is502() {
        CustomException ex = createFailsWhenIndividualLookupThrows(new IndividualApiException(500, ""));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("failed to validate individual ID", ex.getMessage());
    }

    private CustomException onboardFailsWhenIndividualCreateThrows(RuntimeException e) {
        Mockito.when(keycloak.createUser(anyString(), any(), any())).thenReturn("kc-1");
        Mockito.when(individual.createIndividual(any(), any(), any(), any())).thenThrow(e);
        OnboardUser user = new OnboardUser();
        user.setMobileNumber("9999999999");
        user.setPassword("secret");
        OnboardRequest req = new OnboardRequest();
        req.setUser(user);
        req.setIndividual(om.createObjectNode().put("givenName", "A"));
        req.setEmployee(withoutCode());
        CustomException ex = assertThrows(CustomException.class,
                () -> svc.onboardEmployee(req, "P1", "Bearer x", "u1"));
        Mockito.verify(keycloak).deleteUser("P1", "kc-1", "Bearer x");
        return ex;
    }

    @Test
    void individualCreateRejected_is400WithReason() {
        CustomException ex = onboardFailsWhenIndividualCreateThrows(new IndividualApiException(400,
                digitError("VALIDATION_ERROR", "at least one of mobileNumber or email is required")));
        assertEquals("INVALID_REQUEST", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("failed to create individual: at least one of mobileNumber or email is required", ex.getMessage());
    }

    @Test
    void individualCreateConflict_is409WithReason() {
        CustomException ex = onboardFailsWhenIndividualCreateThrows(new IndividualApiException(409,
                digitError("CONFLICT", "individual already exists")));
        assertEquals("CONFLICT", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        assertEquals("failed to create individual: individual already exists", ex.getMessage());
    }

    @Test
    void individualCreateForbidden_is502() {
        CustomException ex = onboardFailsWhenIndividualCreateThrows(new IndividualApiException(403, ""));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("failed to create individual", ex.getMessage());
    }
}
