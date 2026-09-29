package com.digit.employee.config;

import com.digit.employee.web.HeaderInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers required-header enforcement on the API group only, keeping it off health, actuator and
 * /internal/*. Patterns are relative to the servlet context path, so {@code /v3/**} resolves to
 * {@code /employee/v3/**}.
 *
 * <p>The canonical path group is excluded: it carries tenant/user in the request body's
 * RequestMetadata block rather than in headers, and reproduces the same checks against that block.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String canonicalApiPrefix;

    public WebMvcConfig(@Value("${employee.server.canonical-api-prefix:canonical}") String canonicalApiPrefix) {
        this.canonicalApiPrefix = canonicalApiPrefix;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HeaderInterceptor())
                .addPathPatterns("/v3/**")
                .excludePathPatterns("/v3/" + segment(canonicalApiPrefix) + "/**");
    }

    private static String segment(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? "canonical" : trimmed;
    }
}
