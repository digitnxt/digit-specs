package com.digit.account.service;

import com.digit.account.config.AccountProperties;
import com.digit.account.model.Mappers;
import com.digit.account.model.TenantConfigCreateRequest;
import com.digit.account.model.TenantConfigEntity;
import com.digit.account.model.TenantConfigUpdateRequest;
import com.digit.account.model.TenantEntity;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PUT /config/{id}: a version the client sends must be current; every update is a compare-and-swap. */
class TenantConfigVersionTest {

    private TenantConfigRepository configRepo;
    private TenantRepository tenantRepo;
    private TenantConfigService service;

    @BeforeEach
    void setUp() {
        configRepo = mock(TenantConfigRepository.class);
        tenantRepo = mock(TenantRepository.class);
        service = new TenantConfigService(configRepo, tenantRepo,
                mock(EventPublisher.class), new AccountProperties());

        TenantConfigEntity existing = new TenantConfigEntity();
        existing.setId("cfg-1");
        existing.setTenantId("CITYA");
        existing.setConfigKey("theme.color");
        existing.setConfigValue("blue");
        existing.setActive(true);
        existing.setVersion(2);
        when(configRepo.getById("cfg-1", "CITYA")).thenReturn(existing);
        when(configRepo.update(any(TenantConfigEntity.class), anyInt())).thenReturn(true);
    }

    private static TenantConfigUpdateRequest value(String v, Integer version) {
        TenantConfigUpdateRequest r = new TenantConfigUpdateRequest();
        r.setConfigValue(v);
        r.setVersion(version);
        return r;
    }

    private CustomException fails(TenantConfigUpdateRequest r) {
        return assertThrows(CustomException.class, () -> service.update("cfg-1", r, "tester", "req-1", "CITYA"));
    }

    @Test
    void aMissingVersionUpdatesAgainstTheVersionJustRead() {
        assertEquals(3, service.update("cfg-1", value("red", null), "tester", "req-1", "CITYA").getVersion());
        verify(configRepo).update(any(TenantConfigEntity.class), eq(2));
    }

    @Test
    void aMissingVersionStillConflictsWithAConcurrentWrite() {
        when(configRepo.update(any(TenantConfigEntity.class), anyInt())).thenReturn(false);
        CustomException ex = fails(value("red", null));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
    }

    @Test
    void aStaleVersionIsAConflictBeforeAnyWrite() {
        CustomException ex = fails(value("red", 1));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        verify(configRepo, never()).update(any(TenantConfigEntity.class), anyInt());
    }

    @Test
    void losingTheRaceAtTheWriteIsAConflict() {
        when(configRepo.update(any(TenantConfigEntity.class), anyInt())).thenReturn(false);
        CustomException ex = fails(value("red", 2));
        assertEquals("ROW_VERSION_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
    }

    @Test
    void theCurrentVersionUpdatesAndBumpsIt() {
        assertEquals(3, service.update("cfg-1", value("red", 2), "tester", "req-1", "CITYA").getVersion());
        verify(configRepo).update(any(TenantConfigEntity.class), eq(2));
    }

    @Test
    void aNewConfigStartsAtVersionOne() {
        TenantConfigCreateRequest req = new TenantConfigCreateRequest();
        req.setConfigKey("k");
        req.setConfigValue("v");
        when(tenantRepo.getByCode("CITYA")).thenReturn(new TenantEntity());
        assertEquals(1, service.create(req, "CITYA", "tester", "req-1").getVersion());
        verify(configRepo).create(argThat(c -> c.getVersion() == 1));
    }

    @Test
    void versionZeroIsStillSerialized() {
        // Rows created before configs started at 1 are still at 0, and a client needs it to update them.
        TenantConfigEntity fresh = new TenantConfigEntity();
        fresh.setId("cfg-2");
        fresh.setTenantId("CITYA");
        fresh.setConfigKey("k");
        fresh.setConfigValue("v");
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        String json = mapper.writeValueAsString(Mappers.tenantConfigFromEntity(fresh));
        assertTrue(json.contains("\"version\":0"), json);
    }
}
