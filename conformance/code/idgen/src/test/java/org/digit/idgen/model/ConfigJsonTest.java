package org.digit.idgen.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Locks the JSONB config shape to what the Go service persisted (HR-1):
 * every field present, nulls included, "char" key for padding, and legacy
 * Go-written rows deserialize losslessly.
 */
class ConfigJsonTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void normalizedConfigSerializesLikeGo() {
        var cfg = new TemplateConfig("{ORG}-{SEQ}", null, null).normalized();
        var expected = """
                {"template":"{ORG}-{SEQ}",\
                "sequence":{"scope":"GLOBAL","start":1,"padding":null},\
                "random":{"length":2,"charset":"A-Z0-9"}}""";
        assertThat(mapper.readTree(mapper.writeValueAsString(cfg)))
                .isEqualTo(mapper.readTree(expected));
    }

    @Test
    void paddingSerializesWithCharKey() {
        var cfg = new TemplateConfig("{SEQ}",
                new TemplateConfig.Sequence(SequenceScope.DAILY, 100,
                        new TemplateConfig.Padding(6, "0")),
                null).normalized();
        var json = mapper.writeValueAsString(cfg);
        assertThat(json).contains("\"char\":\"0\"").contains("\"scope\":\"DAILY\"");
    }

    @Test
    void goWrittenJsonbRoundTrips() {
        // captured shape of a row the Go service writes (json.Marshal, no omitempty)
        var goJson = """
                {"template":"BILL-{BSCODE}-{DATE:yyyy}-{SEQ}-{RAND}",\
                "sequence":{"scope":"YEARLY","start":1,"padding":{"length":4,"char":"0"}},\
                "random":{"length":2,"charset":"A-Z0-9"}}""";
        var cfg = mapper.readValue(goJson, TemplateConfig.class);
        assertThat(cfg.sequence().scope()).isEqualTo(SequenceScope.YEARLY);
        assertThat(cfg.sequence().padding().character()).isEqualTo("0");
        assertThat(cfg.random().charset()).isEqualTo("A-Z0-9");
        assertThat(mapper.readTree(mapper.writeValueAsString(cfg))).isEqualTo(mapper.readTree(goJson));
    }
}
