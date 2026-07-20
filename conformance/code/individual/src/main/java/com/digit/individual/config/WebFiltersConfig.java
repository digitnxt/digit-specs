package com.digit.individual.config;

import com.digit.individual.web.HeaderValidationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the header-validation filter on the API group only (mirrors Go: ExtractHeaders is
 * applied to the {@code api} group, keeping it off /health and /internal/migrate). Order 35 places
 * it after observability filters and before the shared TenantTransactionFilter (order 40), matching
 * the Go middleware order (ExtractHeaders → tenantdb.GinMiddleware).
 */
@Configuration
public class WebFiltersConfig {

    @Bean
    public FilterRegistrationBean<HeaderValidationFilter> headerValidationFilter(IndividualProperties props) {
        FilterRegistrationBean<HeaderValidationFilter> reg =
                new FilterRegistrationBean<>(new HeaderValidationFilter());
        String ctx = props.getServer().getContextPath();
        if (ctx == null || ctx.isEmpty()) {
            ctx = "";
        }
        // API group lives under <context-path>/v3/... — scope the filter to that subtree.
        reg.addUrlPatterns(ctx + "/v3/*");
        reg.setOrder(35);
        return reg;
    }
}
