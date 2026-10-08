package com.digit.account.service;

import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.config.AccountProperties;
import com.digit.account.model.TenantEntity;
import com.digit.account.model.TenantUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Enforcement side of isActive: flipping it must enable/disable the tenant's Keycloak realm. */
class TenantDeactivationTest {

    private TenantRepository tenantRepo;
    private KeycloakClient keycloak;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenantRepo = mock(TenantRepository.class);
        keycloak = mock(KeycloakClient.class);
        service = new TenantService(tenantRepo, mock(TenantConfigRepository.class), keycloak,
                mock(com.digit.account.clients.notification.NotificationClient.class),
                mock(com.digit.account.clients.otp.OtpClient.class),
                mock(EventPublisher.class), new AccountProperties(), new ObjectMapper());
    }

    private void existing(boolean active) {
        TenantEntity e = new TenantEntity();
        e.setId("id-1");
        e.setCode("CITYA");
        e.setName("City A");
        e.setEmail("a@example.com");
        e.setActive(active);
        when(tenantRepo.getById("id-1")).thenReturn(e);
        when(tenantRepo.update(any(TenantEntity.class), anyInt())).thenReturn(true);
    }

    private static TenantUpdateRequest req(Boolean isActive) {
        TenantUpdateRequest r = new TenantUpdateRequest();
        r.setVersion(0);
        r.setIsActive(isActive);
        return r;
    }

    @Test
    void deactivatingDisablesTheRealmBeforeTheDbWrite() {
        existing(true);
        service.update("id-1", req(false), "tester", "req-1");

        // Order matters: the realm must be locked down first so a failed DB write cannot leave a
        // tenant marked inactive whose users can still authenticate.
        InOrder order = inOrder(keycloak, tenantRepo);
        order.verify(keycloak).setRealmEnabled("CITYA", false);
        order.verify(tenantRepo).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void reactivatingEnablesTheRealm() {
        existing(false);
        service.update("id-1", req(true), "tester", "req-1");
        verify(keycloak).setRealmEnabled("CITYA", true);
    }

    @Test
    void aPatchThatDoesNotTouchIsActiveLeavesTheRealmAlone() {
        existing(true);
        service.update("id-1", req(null), "tester", "req-1");
        verify(keycloak, never()).setRealmEnabled(anyString(), anyBoolean());
    }

    @Test
    void sendingTheSameValueIsANoOpOnKeycloak() {
        existing(true);
        service.update("id-1", req(true), "tester", "req-1");
        verify(keycloak, never()).setRealmEnabled(anyString(), anyBoolean());
    }

    @Test
    void aFailedRealmFlipAbortsBeforeTouchingTheDb() {
        existing(true);
        doThrow(new RuntimeException("keycloak down")).when(keycloak).setRealmEnabled("CITYA", false);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.update("id-1", req(false), "tester", "req-1"));
        assertTrue(ex.getMessage().contains("failed to disable Keycloak realm"), ex.getMessage());
        verify(tenantRepo, never()).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void aFailedDbWriteRestoresThePreviousRealmState() {
        existing(true);
        doThrow(new RuntimeException("db down")).when(tenantRepo).update(any(TenantEntity.class), anyInt());

        assertThrows(RuntimeException.class,
                () -> service.update("id-1", req(false), "tester", "req-1"));
        verify(keycloak).setRealmEnabled("CITYA", false);
        verify(keycloak).setRealmEnabled("CITYA", true);
    }

    @Test
    void aFailedRollbackSaysManualCleanupIsNeeded() {
        existing(true);
        doThrow(new RuntimeException("db down")).when(tenantRepo).update(any(TenantEntity.class), anyInt());
        doThrow(new RuntimeException("keycloak down")).when(keycloak).setRealmEnabled("CITYA", true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.update("id-1", req(false), "tester", "req-1"));
        assertTrue(ex.getMessage().contains("manual cleanup required"), ex.getMessage());
    }

    @Test
    void aFailedDbWriteWithNoFlipDoesNotTouchKeycloak() {
        existing(true);
        doThrow(new RuntimeException("db down")).when(tenantRepo).update(any(TenantEntity.class), anyInt());

        assertThrows(RuntimeException.class,
                () -> service.update("id-1", req(null), "tester", "req-1"));
        verify(keycloak, never()).setRealmEnabled(anyString(), anyBoolean());
    }
}