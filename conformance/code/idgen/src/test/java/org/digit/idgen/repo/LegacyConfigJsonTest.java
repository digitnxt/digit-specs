package org.digit.idgen.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.digit.idgen.model.SequenceScope;
import org.digit.idgen.model.TemplateConfig;
import org.junit.jupiter.api.Test;

/**
 * Locks the production JSONB mapper against real Go-era legacy rows
 * (found in a live idgen_db during Phase 6): pre-validation scopes were
 * stored lowercase ("daily") — Go's string-typed enum read them silently.
 */
class LegacyConfigJsonTest {

    @Test
    void lowercaseLegacyScopeDeserializes() {
        var goLegacyJson = """
                {"random":{"length":2,"charset":"A-Z0-9"},\
                "sequence":{"scope":"daily","start":1,"padding":{"char":"0","length":4}},\
                "template":"BILL-{BSCODE}-{DATE:yyyy}-{SEQ}-{RAND}"}""";
        var cfg = TemplateRepository.MAPPER.readValue(goLegacyJson, TemplateConfig.class);
        assertThat(cfg.sequence().scope()).isEqualTo(SequenceScope.DAILY);
        assertThat(cfg.sequence().padding().character()).isEqualTo("0");
    }
}
