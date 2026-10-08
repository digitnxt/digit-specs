package com.digit.account.service;

import com.digit.account.cache.SignupCache;
import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.clients.otp.OtpClient;
import com.digit.account.config.AccountProperties;
import com.digit.account.model.TenantCreateRequest;
import com.digit.account.model.TenantEntity;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.digit.account.web.DatabaseExceptionHandler;
import com.digit.account.web.TenantController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Infrastructure failures are reported as the service's fault (5xx), never as a 400. */
class TenantInfraFailureTest {

    private TenantRepository tenantRepo;
    private KeycloakClient keycloak;
    private OtpClient otp;
    private SignupCache cache;
    private TenantService service;
    private TenantController controller;

    @BeforeEach
    void setUp() {
        tenantRepo = mock(TenantRepository.class);
        keycloak = mock(KeycloakClient.class);
        otp = mock(OtpClient.class);
        cache = mock(SignupCache.class);
        service = new TenantService(tenantRepo, mock(TenantConfigRepository.class), keycloak,
                mock(com.digit.account.clients.notification.NotificationClient.class), otp,
                mock(EventPublisher.class), new AccountProperties(), new ObjectMapper());
        controller = new TenantController(service, otp, cache, new ObjectMapper());
    }

    private static TenantCreateRequest createRequest() {
        TenantCreateRequest r = new TenantCreateRequest();
        r.setName("City A");
        r.setEmail("admin@citya.example.org");
        return r;
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ Keycloak

    @Test
    void aKeycloakFailureOnCreateIsA502AndRollsBackTheRow() {
        doThrow(new RuntimeException("failed to get admin token: java.net.ConnectException"))
                .when(keycloak).createRealmWithFullConfig(anyString(), anyString(), anyString(), anyString(), any(), any(Boolean.class));
        CustomException ex = assertThrows(CustomException.class,
                () -> service.create(createRequest(), "tester", "req-1"));
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("failed to create Keycloak realm", ex.getMessage());
        verify(tenantRepo).delete(any());
    }

    @Test
    void aKeycloakFailureOnDeleteIsA502() {
        TenantEntity e = new TenantEntity();
        e.setId("id-1");
        e.setCode("CITYA");
        when(tenantRepo.getById("id-1")).thenReturn(e);
        doThrow(new RuntimeException("connection reset")).when(keycloak).deleteRealm("CITYA");
        CustomException ex = assertThrows(CustomException.class, () -> service.deleteById("id-1", "tester"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("failed to delete Keycloak realm", ex.getMessage());
    }

    // ------------------------------------------------------------------ database

    @Test
    void aDatabaseFailureOnCreateReachesTheDatabaseHandlerUnwrapped() {
        CannotGetJdbcConnectionException down = new CannotGetJdbcConnectionException("connection refused");
        doThrow(down).when(tenantRepo).create(any(TenantEntity.class));
        assertSame(down, assertThrows(CannotGetJdbcConnectionException.class,
                () -> service.create(createRequest(), "tester", "req-1")));
    }

    @Test
    void theDatabaseHandlerReportsAFailureAs500AndARejectedValueAs400() {
        DatabaseExceptionHandler handler = new DatabaseExceptionHandler();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleDatabaseFailure(new CannotGetJdbcConnectionException("down")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleIntegrityViolation(new DataIntegrityViolationException("value too long")).getStatusCode());
    }

    // ------------------------------------------------------------------ signup

    @Test
    void anUnreachableOtpServiceIsA503() {
        when(cache.get(anyString())).thenReturn(createRequest());
        when(otp.resend(anyString(), any())).thenThrow(new RuntimeException("OTP service request failed: timeout"));
        CustomException ex = assertThrows(CustomException.class,
                () -> controller.resendSignupOtp(new MockHttpServletRequest(), json("{\"referenceId\":\"ref-0123456789\"}")));
        assertEquals("OTP_SERVICE_ERROR", ex.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
        assertEquals("Failed to resend OTP", ex.getMessage());
    }

    @Test
    void anExpiredRegistrationIsStillA422() {
        when(cache.get(anyString())).thenReturn(null);
        CustomException ex = assertThrows(CustomException.class, () -> controller.verifySignup("tester", "req-1",
                json("{\"referenceId\":\"ref-0123456789\",\"otp\":\"123456\",\"purpose\":\"registration\"}")));
        assertEquals("REQUEST_EXPIRED", ex.getCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getHttpStatus());
    }

    @Test
    void aRedisFailureIsA500NotAnExpiredRequest() {
        when(cache.get(anyString())).thenThrow(new RuntimeException("failed to retrieve payload from Redis: refused"));
        CustomException ex = assertThrows(CustomException.class, () -> controller.verifySignup("tester", "req-1",
                json("{\"referenceId\":\"ref-0123456789\",\"otp\":\"123456\",\"purpose\":\"registration\"}")));
        assertEquals("CACHE_ERROR", ex.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getHttpStatus());
    }
}
