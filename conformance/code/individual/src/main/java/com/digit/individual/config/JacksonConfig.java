package com.digit.individual.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot 4 uses Jackson 3 (tools.jackson.databind) for MVC. This service standardizes on the
 * Jackson 3 mapper everywhere (request/response + raw jsonb read/write) so the dynamic
 * additionalAttributes / uniquenessCriteria payloads round-trip consistently.
 *
 * <p>The auto-configured MVC JsonMapper is customized to ignore unknown properties (Go's lenient
 * default for query/search). A separate STRICT JsonMapper bean reproduces Go's
 * {@code json.Decoder.DisallowUnknownFields()} used on create/update/config-upsert bodies.
 */
@Configuration
public class JacksonConfig {

    /** Customizes the MVC mapper to ignore unknown fields (used for response serialization + jsonb). */
    @Bean
    public JsonMapperBuilderCustomizer lenientJsonMapperCustomizer() {
        return builder -> builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Strict mapper used by the controllers for manual body parsing — unknown fields cause a parse
     * error, mirroring the Go handlers' DisallowUnknownFields. Not @Primary, injected by name.
     */
    @Bean(name = "strictJsonMapper")
    public JsonMapper strictJsonMapper() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .build();
    }

    /**
     * Jackson 2 ObjectMapper required by the official org.digit:tracer auto-configuration
     * (ErrorQueueProducer / error serialization). The service's own code uses the Jackson 3
     * JsonMapper above; this bean exists solely to satisfy the tracer's dependency on the legacy
     * com.fasterxml.jackson.databind.ObjectMapper.
     */
    @Bean(name = "tracerObjectMapper")
    public com.fasterxml.jackson.databind.ObjectMapper tracerObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
