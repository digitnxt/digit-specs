package com.digit.accesscontrol.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Customizes Spring Boot 4's auto-configured Jackson 3 {@code ObjectMapper} (the JSON stack used by
 * the web message converters AND injected into the controllers/repositories) so unknown properties
 * are ignored — matching Go's lenient JSON unmarshalling. We customize Boot's mapper rather than
 * defining our own primary bean so a single Jackson 3 mapper handles both response serialization
 * and our manual body parsing (keeps raw jsonb {@code JsonNode} passthrough working).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer accessControlJsonMapperCustomizer() {
        return builder -> builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * A Jackson 2 ({@code com.fasterxml}) ObjectMapper required solely by the official org.digit:tracer
     * auto-configuration (its StructuredLogger / ErrorQueueProducer depend on a {@code com.fasterxml}
     * mapper). It is intentionally NOT {@code @Primary} and is a different type from this service's
     * Jackson 3 ({@code tools.jackson}) mapper, so controllers/repositories keep using the
     * Boot-customized Jackson 3 mapper — no ambiguity and no change to service JSON behavior.
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper tracerObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
