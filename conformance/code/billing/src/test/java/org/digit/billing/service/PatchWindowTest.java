package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;

/** The 3 strict-inequality patch rules (Go ValidateBusinessServicePatch). */
class PatchWindowTest {

    private static BusinessServiceRequests.Patch patch(Long from, Long to) {
        return new BusinessServiceRequests.Patch(null, null, null, null, null, null, from, to, null);
    }

    @Test
    void bothProvidedMustBeStrictlyIncreasing() {
        CustomException e = assertThrows(CustomException.class,
                () -> BusinessServiceService.validatePatchWindow(patch(100L, 100L), 0, null));
        assertEquals("INVALID_EFFECTIVE_RANGE", e.getCode());
        assertDoesNotThrow(() -> BusinessServiceService.validatePatchWindow(patch(100L, 101L), 0, null));
        // both provided: existing values are NOT consulted (Go returns early)
        assertDoesNotThrow(() -> BusinessServiceService.validatePatchWindow(patch(100L, 101L), 500, 600L));
    }

    @Test
    void onlyToComparedAgainstExistingFrom() {
        CustomException e = assertThrows(CustomException.class,
                () -> BusinessServiceService.validatePatchWindow(patch(null, 100L), 100, null));
        assertEquals("INVALID_EFFECTIVE_TO", e.getCode());
        assertDoesNotThrow(() -> BusinessServiceService.validatePatchWindow(patch(null, 101L), 100, null));
    }

    @Test
    void onlyFromComparedAgainstExistingTo() {
        CustomException e = assertThrows(CustomException.class,
                () -> BusinessServiceService.validatePatchWindow(patch(200L, null), 0, 200L));
        assertEquals("INVALID_EFFECTIVE_FROM", e.getCode());
        assertDoesNotThrow(() -> BusinessServiceService.validatePatchWindow(patch(199L, null), 0, 200L));
        // no existing effectiveTo → unconstrained
        assertDoesNotThrow(() -> BusinessServiceService.validatePatchWindow(patch(999L, null), 0, null));
    }

    @Test
    void duplicateDetectionReportsEveryDupOnce() {
        CustomException e = assertThrows(CustomException.class, () ->
                BusinessServiceService.validateNoDuplicates(List.of("PT", "TL", "PT", "PT", "WS", "TL"), "codes"));
        assertEquals("DUPLICATE_VALUES", e.getCode());
        assertEquals(List.of("PT", "TL"), e.getParams());
    }
}
