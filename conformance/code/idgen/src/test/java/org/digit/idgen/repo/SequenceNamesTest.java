package org.digit.idgen.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SequenceNamesTest {

    /**
     * Cross-implementation vector: the Go service names sequences
     * sha1("tenantID:templateCode") — verified independently via
     * {@code echo -n "pb:receipt-id" | sha1sum}. Existing databases hold
     * sequences under these names; this must never change.
     */
    @Test
    void matchesGoSequenceNaming() {
        assertThat(SequenceNames.of("pb", "receipt-id"))
                .isEqualTo("seq_v1_c6bbf4885c55c29a2aa400ae7166913d20bf7ebe");
    }

    @Test
    void distinctForSimilarInputs() {
        // the sanitize-to-underscore approach would collide here; SHA1 must not
        assertThat(SequenceNames.of("tenant-a", "code"))
                .isNotEqualTo(SequenceNames.of("tenant_a", "code"));
    }
}
