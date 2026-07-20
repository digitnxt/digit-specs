package com.digit.individual.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #1 null-safety: child collections coalesce a null setter argument to an empty list, so a request
 * body with {@code "addresses": null} (or identifiers/documents) can't NPE downstream — matching
 * Go's nil-slice range being a no-op.
 */
class IndividualNullSafetyTest {

    @Test
    void nullChildCollections_coalesceToEmpty() {
        Individual ind = new Individual();
        ind.setAddresses(null);
        ind.setIdentifiers(null);
        ind.setDocuments(null);

        assertNotNull(ind.getAddresses());
        assertNotNull(ind.getIdentifiers());
        assertNotNull(ind.getDocuments());
        assertTrue(ind.getAddresses().isEmpty());
        assertTrue(ind.getIdentifiers().isEmpty());
        assertTrue(ind.getDocuments().isEmpty());
    }

    @Test
    void toEntity_withNullChildArrays_yieldsEmptyLists() {
        IndividualDTO dto = new IndividualDTO();
        dto.setGivenName("Ada");
        dto.setAddresses(null);
        dto.setIdentifiers(null);
        dto.setDocuments(null);

        Individual e = ModelMappers.toEntity(dto);
        assertNotNull(e.getAddresses());
        assertNotNull(e.getIdentifiers());
        assertNotNull(e.getDocuments());
    }
}
