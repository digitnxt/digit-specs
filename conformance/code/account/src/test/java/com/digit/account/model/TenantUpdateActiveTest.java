package com.digit.account.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** isActive on PUT /tenants/{id}: wire binding plus the "omit to retain" patch semantics. */
class TenantUpdateActiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static TenantEntity existing(boolean active) {
        TenantEntity e = new TenantEntity();
        e.setId("id-1");
        e.setCode("CITYA");
        e.setName("City A");
        e.setEmail("a@example.com");
        e.setActive(active);
        e.setVersion(3);
        return e;
    }

    private static TenantEntity apply(TenantEntity existing, String json) throws Exception {
        TenantUpdateRequest req = MAPPER.readValue(json, TenantUpdateRequest.class);
        return Mappers.tenantUpdateRequestToEntity(existing, req, "tester", "req-1", 1000L);
    }

    @Test
    void bindsIsActiveFromTheWireName() throws Exception {
        // The request must accept the same "isActive" key the response emits, not Jackson's
        // default "active" derived from the getter.
        assertFalse(MAPPER.readValue("{\"isActive\":false}", TenantUpdateRequest.class).getIsActive());
        assertTrue(MAPPER.readValue("{\"isActive\":true}", TenantUpdateRequest.class).getIsActive());
        assertNull(MAPPER.readValue("{\"city\":\"X\"}", TenantUpdateRequest.class).getIsActive());
    }

    @Test
    void deactivatesWhenFalseIsSent() throws Exception {
        assertFalse(apply(existing(true), "{\"isActive\":false}").isActive());
    }

    @Test
    void reactivatesWhenTrueIsSent() throws Exception {
        assertTrue(apply(existing(false), "{\"isActive\":true}").isActive());
    }

    @Test
    void omittingIsActiveRetainsTheCurrentValue() throws Exception {
        // The whole point of boxing the field: a patch that only touches city must not silently
        // reactivate a deactivated tenant.
        assertFalse(apply(existing(false), "{\"city\":\"Pune\"}").isActive());
        assertTrue(apply(existing(true), "{\"city\":\"Pune\"}").isActive());
    }

    @Test
    void flippingIsActiveStillBumpsVersionAndAudit() throws Exception {
        TenantEntity out = apply(existing(true), "{\"isActive\":false}");
        assertEquals(4, out.getVersion());
        assertEquals("tester", out.getModifiedBy());
        assertEquals(1000L, out.getModifiedTime());
    }

    @Test
    void immutableFieldsStayUntouchedWhenFlipping() throws Exception {
        TenantEntity out = apply(existing(true), "{\"isActive\":false}");
        assertEquals("CITYA", out.getCode());
        assertEquals("City A", out.getName());
        assertEquals("a@example.com", out.getEmail());
        assertEquals("id-1", out.getId());
    }
}