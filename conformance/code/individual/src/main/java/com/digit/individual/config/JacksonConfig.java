package com.digit.individual.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

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
        return builder -> builder
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL));
    }

    /**
     * Strict mapper used by the controllers for manual body parsing — unknown fields cause a parse
     * error, mirroring the Go handlers' DisallowUnknownFields. Not @Primary, injected by name.
     */
    @Bean(name = "strictJsonMapper")
    public JsonMapper strictJsonMapper() {
        // Disable scalar coercion for boolean/number types: a wrong-typed value (e.g. isActive:"" or
        // age:"5") is a parse error → 400, not a silently-coerced value that slips past validation
        // into business logic / an external call. Mirrors Go, which rejects any non-matching JSON type.
        JsonMapper.Builder b = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        for (LogicalType t : new LogicalType[]{LogicalType.Boolean, LogicalType.Integer, LogicalType.Float}) {
            b.withCoercionConfig(t, cfg -> cfg
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
        }
        return b.build();
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
