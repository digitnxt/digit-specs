package com.digit.account.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** isActive on PUT /config/{id}: wire binding plus "omit to retain" against the existing row. */
class TenantConfigUpdateActiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static TenantConfigEntity existing(boolean active) {
        TenantConfigEntity e = new TenantConfigEntity();
        e.setId("cfg-1");
        e.setTenantId("CITYA");
        e.setConfigKey("theme.color");
        e.setConfigValue("blue");
        e.setDescription("UI accent");
        e.setActive(active);
        e.setVersion(2);
        return e;
    }

    private static TenantConfigEntity apply(TenantConfigEntity existing, String json) throws Exception {
        TenantConfigUpdateRequest req = MAPPER.readValue(json, TenantConfigUpdateRequest.class);
        return Mappers.tenantConfigUpdateRequestToEntity(existing, req, "tester", "req-1", 5000L);
    }

    /** configKey and configValue are required on this endpoint, so every patch below carries them. */
    private static String body(String extra) {
        return "{\"configKey\":\"theme.color\",\"configValue\":\"blue\"" + extra + "}";
    }

    @Test
    void bindsIsActiveFromTheWireName() throws Exception {
        assertFalse(MAPPER.readValue("{\"isActive\":false}", TenantConfigUpdateRequest.class).getIsActive());
        assertTrue(MAPPER.readValue("{\"isActive\":true}", TenantConfigUpdateRequest.class).getIsActive());
        assertNull(MAPPER.readValue("{}", TenantConfigUpdateRequest.class).getIsActive());
    }

    @Test
    void deactivatesWhenFalseIsSent() throws Exception {
        assertFalse(apply(existing(true), body(",\"isActive\":false")).isActive());
    }

    @Test
    void reactivatesWhenTrueIsSent() throws Exception {
        assertTrue(apply(existing(false), body(",\"isActive\":true")).isActive());
    }

    @Test
    void omittingIsActiveRetainsTheCurrentValue() throws Exception {
        assertFalse(apply(existing(false), body("")).isActive());
        assertTrue(apply(existing(true), body("")).isActive());
    }

    @Test
    void flippingIsActiveStillBumpsVersionAndAudit() throws Exception {
        TenantConfigEntity out = apply(existing(true), body(",\"isActive\":false"));
        assertEquals(3, out.getVersion());
        assertEquals("tester", out.getModifiedBy());
        assertEquals(5000L, out.getModifiedTime());
    }

    @Test
    void flippingIsActiveDoesNotDisturbDescriptionRetention() throws Exception {
        // description is presence-tracked separately; a body that omits it must still retain it
        // even while isActive is being changed.
        TenantConfigEntity out = apply(existing(true), body(",\"isActive\":false"));
        assertEquals("UI accent", out.getDescription());
        assertEquals("CITYA", out.getTenantId());
        assertEquals("cfg-1", out.getId());
    }
}