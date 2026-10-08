package com.digit.account.service;

import com.digit.account.config.AccountProperties;
import com.digit.account.model.TenantConfigEntity;
import com.digit.account.model.TenantConfigResponse;
import com.digit.account.model.TenantConfigUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * PUT /config/{id} is omit-to-retain on every field. The duplicate-key comparison dereferences the
 * merged configKey, so these cover the paths that used to reach it with a null.
 */
class TenantConfigUpdateValidationTest {

    private TenantConfigRepository configRepo;
    private TenantConfigService service;

    @BeforeEach
    void setUp() {
        configRepo = mock(TenantConfigRepository.class);
        service = new TenantConfigService(configRepo, mock(TenantRepository.class),
                mock(EventPublisher.class), new AccountProperties());

        TenantConfigEntity existing = new TenantConfigEntity();
        existing.setId("cfg-1");
        existing.setTenantId("CITYA");
        existing.setConfigKey("theme.color");
        existing.setConfigValue("blue");
        existing.setDescription("UI accent");
        existing.setActive(true);
        when(configRepo.getById("cfg-1", "CITYA")).thenReturn(existing);
        when(configRepo.update(any(TenantConfigEntity.class), anyInt())).thenReturn(true);
    }

    private static TenantConfigUpdateRequest req(String key, String value) {
        TenantConfigUpdateRequest r = new TenantConfigUpdateRequest();
        r.setConfigKey(key);
        r.setConfigValue(value);
        return r;
    }

    private TenantConfigResponse update(TenantConfigUpdateRequest r) {
        r.setVersion(0);
        return service.update("cfg-1", r, "tester", "req-1", "CITYA");
    }

    // ---------------------------------------------------------------- omit-to-retain

    @Test
    void omittingConfigKeyRetainsItInsteadOfCrashing() {
        // This is the exact request that used to NPE on the duplicate-key comparison.
        TenantConfigResponse resp = update(req(null, "green"));
        assertEquals("theme.color", resp.getConfigKey());
        assertEquals("green", resp.getConfigValue());
    }

    @Test
    void omittingConfigValueRetainsIt() {
        TenantConfigResponse resp = update(req("theme.accent", null));
        assertEquals("theme.accent", resp.getConfigKey());
        assertEquals("blue", resp.getConfigValue());
    }

    @Test
    void anEmptyPatchRetainsEverything() {
        TenantConfigResponse resp = update(new TenantConfigUpdateRequest());
        assertEquals("theme.color", resp.getConfigKey());
        assertEquals("blue", resp.getConfigValue());
        assertEquals("UI accent", resp.getDescription());
        assertTrue(resp.isActive());
    }

    @Test
    void isActiveAloneIsNowASufficientPatch() {
        TenantConfigUpdateRequest r = new TenantConfigUpdateRequest();
        r.setIsActive(false);
        TenantConfigResponse resp = update(r);
        assertEquals("theme.color", resp.getConfigKey());
        assertEquals("blue", resp.getConfigValue());
        assertTrue(!resp.isActive());
    }

    // ---------------------------------------------------------------- explicit blanks still invalid

    @Test
    void anExplicitlyBlankConfigKeyIsStillAValidationError() {
        // Blank is a supplied value, not an omission: it would null out a NOT NULL column.
        CustomException ex = assertThrows(CustomException.class, () -> update(req("   ", "green")));
        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertTrue(ex.getMessage().contains("configKey is required"), ex.getMessage());
    }

    @Test
    void anExplicitlyBlankConfigValueIsStillAValidationError() {
        CustomException ex = assertThrows(CustomException.class, () -> update(req("theme.color", "")));
        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertTrue(ex.getMessage().contains("configValue is required"), ex.getMessage());
    }

    @Test
    void anInvalidRequestNeverProbesForDuplicates() {
        assertThrows(CustomException.class, () -> update(req("   ", "green")));
        verify(configRepo, never()).getByKey(anyString(), anyString());
    }

    // ---------------------------------------------------------------- duplicate detection intact

    @Test
    void anOmittedKeySkipsTheDuplicateProbe() {
        // Retaining the key means it cannot collide with itself, so no probe is warranted.
        update(req(null, "green"));
        verify(configRepo, never()).getByKey(anyString(), anyString());
        verify(configRepo).update(any(TenantConfigEntity.class), anyInt());
    }

    @Test
    void anUnchangedKeySkipsTheDuplicateProbe() {
        update(req("theme.color", "green"));
        verify(configRepo, never()).getByKey(anyString(), anyString());
    }

    @Test
    void aChangedKeyIsStillCheckedForDuplicates() {
        when(configRepo.getByKey("CITYA", "theme.accent")).thenReturn(null);
        update(req("theme.accent", "green"));
        verify(configRepo).getByKey("CITYA", "theme.accent");
    }

    @Test
    void aChangedKeyThatCollidesStillConflicts() {
        TenantConfigEntity other = new TenantConfigEntity();
        other.setId("cfg-2");
        other.setTenantId("CITYA");
        other.setConfigKey("theme.accent");
        when(configRepo.getByKey("CITYA", "theme.accent")).thenReturn(other);

        CustomException ex = assertThrows(CustomException.class,
                () -> update(req("theme.accent", "green")));
        assertEquals("DUPLICATE_RECORD", ex.getCode());
    }

    @Test
    void aKeyChangeThatCollidesWithItselfIsNotAConflict() {
        // getByKey returning this very row must not be read as a duplicate.
        TenantConfigEntity self = new TenantConfigEntity();
        self.setId("cfg-1");
        self.setTenantId("CITYA");
        self.setConfigKey("theme.accent");
        when(configRepo.getByKey("CITYA", "theme.accent")).thenReturn(self);

        assertEquals("theme.accent", update(req("theme.accent", "green")).getConfigKey());
    }
}