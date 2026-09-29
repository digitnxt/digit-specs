package com.digit.employee.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Provides the application ObjectMapper used by both the MVC message converters and the manual body
 * parsing in the controllers. Unknown properties are ignored to match Go's lenient JSON unmarshalling.
 *
 * <p>Scalar coercion is disabled for boolean/number types: a wrong-typed value (e.g. {@code
 * isActive:""} or {@code version:"3"}) is a parse error → 400, not a silently-coerced value that
 * slips past validation into an external/DB call. Mirrors Go, which rejects any non-matching JSON
 * type for a typed field.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // Default serialization omits null-valued fields so the tracer's Error POJO (whose new
        // description/params fields default to null) renders the 2-field {code,message} envelope the
        // service was verified against. Per-field @JsonInclude annotations on DTOs still take precedence.
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        for (LogicalType t : new LogicalType[]{LogicalType.Boolean, LogicalType.Integer, LogicalType.Float}) {
            mapper.coercionConfigFor(t)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        }
        return mapper;
    }

    /**
     * Boot 4's MVC message converters and the tracer's central exception advice serialize responses
     * via the auto-configured Jackson 3 ({@code tools.jackson}) mapper — NOT the {@code @Primary}
     * Jackson 2 bean above (which is used for manual body parsing and the header filter). Apply the
     * same NON_NULL default here so the tracer's {@code Error} POJO renders the 2-field
     * {@code {code,message}} envelope (dropping the null description/params) that Go emits.
     */
    @Bean
    public JsonMapperBuilderCustomizer employeeResponseInclusionCustomizer() {
        return builder -> builder.changeDefaultPropertyInclusion(
                incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
