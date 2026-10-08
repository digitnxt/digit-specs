package com.digit.account.service;

import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.config.AccountProperties;
import com.digit.account.model.TenantEntity;
import com.digit.account.model.TenantUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.digit.account.validator.TenantValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Optional X-Tenant-Id on the id-addressed tenant endpoints. Absent means a platform caller working
 * across the registry; present confines the caller to that one tenant. The header carries a code
 * because a tenant has no id separate from it.
 */
class TenantIdScopeTest {

    private TenantRepository tenantRepo;
    private TenantConfigRepository configRepo;
    private KeycloakClient keycloak;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenantRepo = mock(TenantRepository.class);
        configRepo = mock(TenantConfigRepository.class);
        keycloak = mock(KeycloakClient.class);
        service = new TenantService(tenantRepo, configRepo, keycloak,
                mock(com.digit.account.clients.notification.NotificationClient.class),
                mock(com.digit.account.clients.otp.OtpClient.class),
                mock(EventPublisher.class), new AccountProperties(), new ObjectMapper());

        TenantEntity e = new TenantEntity();
        e.setId("id-1");
        e.setCode("CITYA");
        e.setName("City A");
        e.setEmail("a@example.com");
        e.setActive(true);
        when(tenantRepo.getById("id-1")).thenReturn(e);
        when(tenantRepo.update(any(TenantEntity.class), anyInt())).thenReturn(true);
    }

    private static TenantUpdateRequest req() {
        TenantUpdateRequest r = new TenantUpdateRequest();
        r.setVersion(0);
        r.setCity("Springfield");
        return r;
    }

    // ------------------------------------------------------------------ update

    @Test
    void anAbsentTenantIdLeavesUpdateUnscoped() {
        // The pre-existing platform behaviour: no header, no restriction.
        service.update("id-1", req(), "tester", "req-1", null);
        verify(tenantRepo).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void aBlankTenantIdIsTreatedAsAbsent() {
        service.update("id-1", req(), "tester", "req-1", "   ");
        verify(tenantRepo).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void aMatchingTenantIdAllowsUpdate() {
        service.update("id-1", req(), "tester", "req-1", "CITYA");
        verify(tenantRepo).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void aMismatchedTenantIdForbidsUpdateBeforeAnyWrite() {
        CustomException ex = assertThrows(CustomException.class,
                () -> service.update("id-1", req(), "tester", "req-1", "CITYB"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        verify(tenantRepo, never()).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void theForbiddenMessageDoesNotLeakTheRowsOwnCode() {
        // The caller is not entitled to know which tenant the id belongs to.
        CustomException ex = assertThrows(CustomException.class,
                () -> service.update("id-1", req(), "tester", "req-1", "CITYB"));
        assertTrue(ex.getMessage().contains("id-1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("CITYB"), ex.getMessage());
        assertTrue(!ex.getMessage().contains("CITYA"), ex.getMessage());
    }

    @Test
    void scopeIsCaseSensitive() {
        // Codes are uppercase by construction, so a lowercase header is a different tenant, not the
        // same one spelled differently — silently folding case would widen the scope.
        assertThrows(CustomException.class,
                () -> service.update("id-1", req(), "tester", "req-1", "citya"));
    }

    // ------------------------------------------------------------------ delete

    @Test
    void aMatchingTenantIdAllowsDelete() {
        service.deleteById("id-1", "tester", "CITYA");
        verify(tenantRepo).delete("id-1");
    }

    @Test
    void aMismatchedTenantIdForbidsDeleteBeforeTouchingAnything() {
        // Delete is the destructive one: the realm and the config rows must survive a refused call.
        CustomException ex = assertThrows(CustomException.class,
                () -> service.deleteById("id-1", "tester", "CITYB"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        verify(keycloak, never()).deleteRealm(anyString());
        verify(configRepo, never()).deleteByTenant(anyString());
        verify(tenantRepo, never()).delete(anyString());
    }

    @Test
    void anAbsentTenantIdLeavesDeleteUnscoped() {
        service.deleteById("id-1", "tester", null);
        verify(tenantRepo).delete("id-1");
    }

    @Test
    void aMissingRowIsNotFoundRatherThanForbidden() {
        // Ordering: absence is reported before the scope check, so a scoped caller gets the same 404
        // a platform caller would rather than a misleading 403.
        when(tenantRepo.getById("id-9")).thenReturn(null);
        CustomException ex = assertThrows(CustomException.class,
                () -> service.deleteById("id-9", "tester", "CITYB"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getHttpStatus());
    }

    // ------------------------------------------------------------------ list scope folding

    @Test
    void noTenantIdLeavesTheCodeFilterAlone() {
        List<String> errs = new ArrayList<>();
        assertNull(TenantValidator.resolveTenantIdScope(null, null, errs));
        assertEquals("CITYB", TenantValidator.resolveTenantIdScope(null, "CITYB", errs));
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void aTenantIdAloneBecomesTheCodeFilter() {
        List<String> errs = new ArrayList<>();
        assertEquals("CITYA", TenantValidator.resolveTenantIdScope("CITYA", null, errs));
        assertEquals("CITYA", TenantValidator.resolveTenantIdScope("CITYA", "", errs));
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void anAgreeingCodeFilterIsAccepted() {
        List<String> errs = new ArrayList<>();
        assertEquals("CITYA", TenantValidator.resolveTenantIdScope("CITYA", "CITYA", errs));
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void aContradictingCodeFilterIsReportedNotResolved() {
        // Returning an empty page would hide the caller's bug; honouring code would escape the scope.
        List<String> errs = new ArrayList<>();
        assertEquals("CITYA", TenantValidator.resolveTenantIdScope("CITYA", "CITYB", errs));
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.get(0).contains("does not match"), errs.toString());
    }

    // ------------------------------------------------------------------ header format

    @Test
    void anAbsentHeaderIsNotAValidationError() {
        assertTrue(TenantValidator.validateOptionalTenantIdHeader(null).isEmpty());
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("").isEmpty());
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("  ").isEmpty());
    }

    @Test
    void aSuppliedHeaderStillHasToBeWellFormed() {
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("citya").size() == 1);
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("CITY A").size() == 1);
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("CITYA").isEmpty());
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("JOSEP-PARSHAD").isEmpty());
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("JOSEP_PARSHAD").isEmpty());
    }

    @Test
    void theHeaderIsNamedInItsOwnErrorMessage() {
        assertTrue(TenantValidator.validateOptionalTenantIdHeader("citya").get(0)
                .startsWith("X-Tenant-Id"));
    }
}