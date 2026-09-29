package com.digit.account.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
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
 * Application ObjectMapper used by the MVC message converters and the manual body parsing in the
 * controllers. Unknown properties are ignored to match Go's lenient JSON unmarshalling.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // Reject scalar cross-coercion so a wrong-typed field fails to parse (400) exactly
                // like Go's typed json.Unmarshal, instead of silently coercing — e.g. a config with
                // configKey:123 or configValue:true (numbers/booleans into String fields).
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    /**
     * Boot 4's MVC message converters and the tracer's central exception advice serialize responses
     * via the auto-configured Jackson 3 ({@code tools.jackson}) mapper — not the {@code @Primary}
     * Jackson 2 bean above. Apply a NON_NULL default there so the tracer's {@code Error} POJO renders
     * the {@code {code,message}} envelope Go emits (Go's Error has {@code description,omitempty} /
     * {@code params,omitempty}) instead of {@code {code,message,description:null,params:null}}.
     */
    @Bean
    public JsonMapperBuilderCustomizer accountResponseInclusionCustomizer() {
        return builder -> builder.changeDefaultPropertyInclusion(
                incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
