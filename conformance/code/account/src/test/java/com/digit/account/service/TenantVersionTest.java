package com.digit.account.service;

import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.config.AccountProperties;
import com.digit.account.model.Mappers;
import com.digit.account.model.TenantCreateRequest;
import com.digit.account.model.TenantEntity;
import com.digit.account.model.TenantUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PUT /tenants/{id}: a version the client sends must be current; every update is a compare-and-swap. */
class TenantVersionTest {

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

        TenantEntity e = new TenantEntity();
        e.setId("id-1");
        e.setCode("CITYA");
        e.setName("City A");
        e.setEmail("a@example.com");
        e.setActive(true);
        e.setVersion(2);
        when(tenantRepo.getById("id-1")).thenReturn(e);
        when(tenantRepo.update(any(TenantEntity.class), anyInt())).thenReturn(true);
    }

    private static TenantUpdateRequest req(Integer version, Boolean isActive) {
        TenantUpdateRequest r = new TenantUpdateRequest();
        r.setCity("Springfield");
        r.setIsActive(isActive);
        r.setVersion(version);
        return r;
    }

    private CustomException fails(TenantUpdateRequest r) {
        return assertThrows(CustomException.class, () -> service.update("id-1", r, "tester", "req-1"));
    }

    @Test
    void aMissingVersionUpdatesAgainstTheVersionJustRead() {
        assertEquals(3, service.update("id-1", req(null, null), "tester", "req-1").getVersion());
        verify(tenantRepo).update(any(TenantEntity.class), eq(2));
    }

    @Test
    void aMissingVersionStillConflictsWithAConcurrentWrite() {
        when(tenantRepo.update(any(TenantEntity.class), anyInt())).thenReturn(false);
        CustomException ex = fails(req(null, null));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
    }

    @Test
    void aStaleVersionIsAConflictBeforeTheRealmIsTouched() {
        CustomException ex = fails(req(1, false));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        verify(keycloak, never()).setRealmEnabled(anyString(), anyBoolean());
        verify(tenantRepo, never()).update(any(TenantEntity.class), anyInt());
    }

    @Test
    void losingTheRaceAtTheWriteIsAConflictAndRestoresTheRealm() {
        when(tenantRepo.update(any(TenantEntity.class), anyInt())).thenReturn(false);
        CustomException ex = fails(req(2, false));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        InOrder order = inOrder(keycloak);
        order.verify(keycloak).setRealmEnabled("CITYA", false);
        order.verify(keycloak).setRealmEnabled("CITYA", true);
    }

    @Test
    void theCurrentVersionUpdatesAndBumpsIt() {
        assertEquals(3, service.update("id-1", req(2, null), "tester", "req-1").getVersion());
        verify(tenantRepo).update(any(TenantEntity.class), eq(2));
    }

    @Test
    void aNewTenantStartsAtVersionOne() {
        TenantCreateRequest r = new TenantCreateRequest();
        r.setName("City B");
        r.setEmail("admin@cityb.example.org");
        assertEquals(1, service.create(r, "tester", "req-1").getVersion());
        verify(tenantRepo).create(argThat(t -> t.getVersion() == 1));
    }

    @Test
    void versionZeroIsStillSerialized() {
        // Tenants created before tenants started at 1 are still at 0, and a client needs it to update them.
        TenantEntity old = new TenantEntity();
        old.setId("id-0");
        old.setCode("OLD");
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        String json = mapper.writeValueAsString(Mappers.tenantFromEntity(old));
        assertTrue(json.contains("\"version\":0"), json);
    }
}
