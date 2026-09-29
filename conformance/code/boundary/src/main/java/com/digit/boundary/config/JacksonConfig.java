package com.digit.boundary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;

/**
 * Spring Boot 4 uses Jackson 3 ({@code tools.jackson.databind}) for the MVC message converters.
 * The boundary service round-trips raw jsonb (geometry, additionalAttributes) and the boundary
 * hierarchy tree, so it standardizes on the SAME Jackson 3 mapper for request/response, manual
 * body parsing and jsonb read/write. We customize Boot's auto-configured JsonMapper to ignore
 * unknown properties (Go's lenient JSON unmarshalling) and expose a JsonMapper bean for manual use.
 */
@Configuration
public class JacksonConfig {

    /** Make Boot's MVC JsonMapper lenient about unknown fields (matches Go json.Unmarshal). */
    @Bean
    public JsonMapperBuilderCustomizer boundaryJsonMapperCustomizer() {
        return (JsonMapper.Builder builder) ->
                builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Standalone JsonMapper for repositories/services (manual jsonb + body parsing). */
    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * Jackson 2 ObjectMapper required by the official tracer's ErrorQueueProducer/ExceptionAdvice beans.
     * NOT {@code @Primary}: boundary's MVC serialization deliberately uses the Jackson 3 mapper above
     * for raw jsonb passthrough; this bean exists only to satisfy the tracer auto-configuration.
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper tracerObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
