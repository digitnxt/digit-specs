package com.digit.account.config;

import com.digit.account.security.PlatformAuthFilter;
import com.digit.account.security.PlatformTokenVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the platform-auth filter. The verifier is built eagerly so a malformed issuer or JWKS
 * URL fails startup rather than every request.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PlatformTokenVerifier platformTokenVerifier(AccountProperties props) {
        return new PlatformTokenVerifier(props);
    }

    @Bean
    public FilterRegistrationBean<PlatformAuthFilter> platformAuthFilter(
            AccountProperties props, PlatformTokenVerifier verifier) {
        FilterRegistrationBean<PlatformAuthFilter> reg =
                new FilterRegistrationBean<>(new PlatformAuthFilter(props, verifier));
        reg.addUrlPatterns("/v3/*");
        // Ahead of the tracer's filter: a request with no right to be here should not be logged as a
        // business call or acquire a correlation id.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}