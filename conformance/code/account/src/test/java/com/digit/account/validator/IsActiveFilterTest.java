package com.digit.account.validator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The optional isActive list filter: absent = no filter, and unrecognised values are errors. */
class IsActiveFilterTest {

    private static TenantValidator.ListQueryParams tenant(String isActive, List<String> errs) {
        return TenantValidator.validateTenantListQuery(null, null, null, isActive, null, null, errs);
    }

    private static TenantConfigValidator.ListQueryParams config(String isActive, List<String> errs) {
        return TenantConfigValidator.validateTenantConfigListQuery("CITYA", null, isActive, null,
                null, errs);
    }

    @Test
    void absentMeansNoFilter() {
        List<String> errs = new ArrayList<>();
        assertNull(tenant(null, errs).isActive);
        assertNull(tenant("", errs).isActive);
        assertNull(tenant("   ", errs).isActive);
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void parsesBothLiterals() {
        List<String> errs = new ArrayList<>();
        assertTrue(tenant("true", errs).isActive);
        assertFalse(tenant("false", errs).isActive);
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void isCaseInsensitiveAndTolerantOfSurroundingSpace() {
        List<String> errs = new ArrayList<>();
        assertTrue(tenant("TRUE", errs).isActive);
        assertFalse(tenant(" False ", errs).isActive);
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void rejectsUnrecognisedValuesRatherThanTreatingThemAsFalse() {
        // Boolean.parseBoolean would map every one of these to false and silently return only the
        // inactive rows, which is the trap this filter has to avoid.
        for (String bad : new String[]{"ture", "yes", "1", "0", "no", "active"}) {
            List<String> errs = new ArrayList<>();
            assertNull(tenant(bad, errs).isActive, bad);
            assertEquals(1, errs.size(), bad);
            assertTrue(errs.get(0).contains("isActive filter must be true or false"), errs.toString());
        }
    }

    @Test
    void theConfigListFilterBehavesIdentically() {
        List<String> errs = new ArrayList<>();
        assertNull(config(null, errs).isActive);
        assertTrue(config("true", errs).isActive);
        assertFalse(config("false", errs).isActive);
        assertTrue(errs.isEmpty(), errs.toString());

        List<String> bad = new ArrayList<>();
        assertNull(config("maybe", bad).isActive);
        assertTrue(bad.stream().anyMatch(m -> m.contains("isActive filter must be true or false")),
                bad.toString());
    }

    @Test
    void aBadFilterDoesNotSuppressOtherQueryErrors() {
        List<String> errs = new ArrayList<>();
        TenantValidator.validateTenantListQuery(null, null, null, "nope", "0", null, errs);
        assertTrue(errs.stream().anyMatch(m -> m.contains("isActive filter")), errs.toString());
        assertTrue(errs.stream().anyMatch(m -> m.contains("page must be at least 1")), errs.toString());
    }
}