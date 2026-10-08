package org.digit.billing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Final-review pin (finding #2): the two shopspring-parity mechanisms are only
 * exercised end-to-end in Phase 6 — these tests fail fast if a future change
 * drops the parse-trim deserializer or the plain-string serializer, silently
 * breaking the Go byte-parity TEST_RESULTS.md established.
 */
class DecimalParityTest {

    private final JsonMapper mapper;

    DecimalParityTest() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().bigDecimalGoParity().customize(builder);
        this.mapper = builder.build();
    }

    private String roundTrip(String json) {
        return mapper.writeValueAsString(mapper.readValue(json, BigDecimal.class));
    }

    @Test
    void parseTrimsTrailingZerosLikeShopspring() {
        // verified against shopspring decimal v1.3.1 (TEST_RESULTS.md)
        assertEquals("\"10.5\"", roundTrip("\"10.50\""));
        assertEquals("\"25\"", roundTrip("\"25.00\""));
        assertEquals("\"0\"", roundTrip("\"0.00\""));
        assertEquals("\"100\"", roundTrip("\"100\""));
        assertEquals("\"0.1\"", roundTrip("\"0.10\""));
        assertEquals("\"100.5\"", roundTrip("100.50")); // number token, same trim
    }

    @Test
    void negativeScaleNeverLeaksScientificNotation() {
        // "100" strips to 1E+2 internally — must still render plain
        BigDecimal stripped = mapper.readValue("\"100\"", BigDecimal.class);
        assertEquals(-2, stripped.scale());
        assertEquals("\"100\"", mapper.writeValueAsString(stripped));
    }

    @Test
    void malformedInputFails() {
        // NumberFormatException / IllegalArgumentException at the mapper level;
        // at the MVC layer either reaches the tracer advice → 400
        assertThrows(RuntimeException.class, () -> mapper.readValue("\"abc\"", BigDecimal.class));
        assertThrows(RuntimeException.class, () -> mapper.readValue("{}", BigDecimal.class));
    }
}
