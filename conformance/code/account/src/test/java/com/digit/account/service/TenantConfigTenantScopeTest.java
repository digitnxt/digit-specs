package com.digit.account.service;

import com.digit.account.config.AccountProperties;
import com.digit.account.model.TenantConfigEntity;
import com.digit.account.model.TenantConfigUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.digit.account.web.TenantConfigController;
import com.digit.account.web.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** PUT /config/{id} only reaches configs owned by the X-Tenant-Id tenant. */
class TenantConfigTenantScopeTest {

    private TenantConfigRepository configRepo;
    private TenantConfigService service;

    @BeforeEach
    void setUp() {
        configRepo = mock(TenantConfigRepository.class);
        service = new TenantConfigService(configRepo, mock(TenantRepository.class),
                mock(EventPublisher.class), new AccountProperties());

        TenantConfigEntity owned = new TenantConfigEntity();
        owned.setId("cfg-1");
        owned.setTenantId("CITYA");
        owned.setConfigKey("theme.color");
        owned.setConfigValue("blue");
        owned.setActive(true);
        when(configRepo.getById("cfg-1", "CITYA")).thenReturn(owned);
        when(configRepo.update(any(TenantConfigEntity.class), anyInt())).thenReturn(true);
    }

    private static TenantConfigUpdateRequest value(String v) {
        TenantConfigUpdateRequest r = new TenantConfigUpdateRequest();
        r.setConfigValue(v);
        r.setVersion(0);
        return r;
    }

    @Test
    void anotherTenantsConfigIsNotFoundAndNotWritten() {
        CustomException ex = assertThrows(CustomException.class,
                () -> service.update("cfg-1", value("hijacked"), "tester", "req-1", "CITYB"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getHttpStatus());
        verify(configRepo).getById("cfg-1", "CITYB");
        verify(configRepo, never()).update(any(TenantConfigEntity.class), anyInt());
    }

    @Test
    void theOwningTenantCanUpdate() {
        assertEquals("green", service.update("cfg-1", value("green"), "tester", "req-1", "CITYA").getConfigValue());
        verify(configRepo).update(any(TenantConfigEntity.class), anyInt());
    }

    @Test
    void aMissingTenantHeaderIsRejectedBeforeAnyLookup() {
        TenantConfigController controller = new TenantConfigController(service, new ObjectMapper());
        byte[] body = "{\"configValue\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(ValidationException.class,
                () -> controller.updateTenantConfig("6f1c2d3e-0000-4000-8000-000000000001", null, "tester", "req-1", body));
        verifyNoInteractions(configRepo);
    }
}
