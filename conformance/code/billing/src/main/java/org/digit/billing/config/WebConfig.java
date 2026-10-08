package org.digit.billing.config;

import com.digit.tenant.migration.config.TenantMigrationProperties;
import org.digit.billing.web.CanonicalController;
import org.digit.billing.web.CanonicalTenantFilter;
import org.digit.billing.web.HeaderInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Resolved value, not the placeholder: interceptor exclude patterns and filter prefixes
     * are matched literally, so unlike {@code @RequestMapping} they cannot carry {@code ${...}}.
     */
    private final String canonicalPath;

    public WebConfig(@Value(CanonicalController.PATH) String canonicalPath) {
        this.canonicalPath = canonicalPath;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The canonical routes carry both required ids in the body: tenant is promoted to a
        // header before this runs, and user id is checked in CanonicalController, on exactly
        // the operations the interceptor demands X-User-ID for, so the error can name the
        // body field the caller actually got wrong.
        registry.addInterceptor(new HeaderInterceptor())
                .addPathPatterns("/v3/**")
                .excludePathPatterns("/v3/" + canonicalPath + "/**");
    }

    /**
     * Order 0: ahead of the tracer's filter (1) and tenant-migration's (40), both of which
     * read X-Tenant-ID and neither of which knows about request bodies.
     */
    @Bean
    public FilterRegistrationBean<CanonicalTenantFilter> canonicalTenantFilter(
            ObjectMapper mapper, TenantMigrationProperties tenantMigration) {

        FilterRegistrationBean<CanonicalTenantFilter> registration = new FilterRegistrationBean<>(
                new CanonicalTenantFilter(canonicalPath, mapper, tenantMigration.isEnabled()));
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }
}
