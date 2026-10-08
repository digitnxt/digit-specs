package org.digit.billing.config;

import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Money fields serialize as quoted decimal strings (HR-1): the contract types
 * them {@code type: string, format: decimal} and the Go service (shopspring
 * default) quotes them. Two shopspring behaviors reproduced, both verified
 * empirically against decimal v1.3.1 (Phase 6 byte-diff):
 * - NewFromString TRIMS trailing zeros at parse ("10.50"→10.5, "25.00"→25) —
 *   mirrored by stripTrailingZeros in the deserializer, so request echoes and
 *   sums of request values render exactly as Go's.
 * - shopspring's DB Scan trims too (a numeric(18,2) 100.50 renders "100.5") —
 *   mirrored by repo/Db.dec stripping at row-mapping; values produced by
 *   arithmetic keep their natural scale in both stacks.
 */
@Configuration
public class JacksonConfig {

    /**
     * Jackson 3 enables {@code SORT_PROPERTIES_ALPHABETICALLY} by default
     * (2.x had it disabled) — it only affects plain field-visibility POJOs
     * like {@link org.digit.billing.model.Bill}/{@link org.digit.billing.model.Payment}
     * (mutable, not records, for the apportion in-place-mutation flow); records
     * are unaffected since their canonical-constructor order wins regardless.
     * Without this, those two responses render alphabetically instead of the
     * declared (Go-matching) field order.
     */
    @Bean
    JsonMapperBuilderCustomizer declarationOrderFieldSerialization() {
        return builder -> builder.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    }

    @Bean
    JsonMapperBuilderCustomizer bigDecimalGoParity() {
        SimpleModule module = new SimpleModule("billing-bigdecimal-go-parity");
        module.addSerializer(BigDecimal.class, new StdSerializer<>(BigDecimal.class) {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(value.toPlainString());
            }
        });
        module.addDeserializer(BigDecimal.class, new StdDeserializer<>(BigDecimal.class) {
            @Override
            public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) {
                String text = p.getValueAsString();
                if (text == null) {
                    // object/array token — fail with a readable message, not an NPE trace
                    throw new IllegalArgumentException("expected a decimal value");
                }
                // stripTrailingZeros("0.00") → 0; ("100") → 1E+2, rendered "100" by toPlainString
                return new BigDecimal(text).stripTrailingZeros();
            }
        });
        return builder -> builder.addModule(module);
    }
}
