package com.digit.employee.config;

import com.digit.employee.web.ForwardHeadersFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires employee-specific response filters. Tracing + metrics filters and the OpenTelemetry SDK are now
 * provided by the official org.digit:tracer library (auto-registered RequestTracingFilter + Micrometer
 * metrics), so this class only keeps the Go-parity {@link ForwardHeadersFilter}, which echoes the
 * X-Tenant-ID / X-User-ID / X-Request-ID headers on responses — behavior not provided by the tracer.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public FilterRegistrationBean<ForwardHeadersFilter> forwardHeadersFilter() {
        FilterRegistrationBean<ForwardHeadersFilter> reg = new FilterRegistrationBean<>(new ForwardHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(10);
        return reg;
    }
}
