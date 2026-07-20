package com.digit.employee.config;

import com.digit.employee.web.HeadersFilter;
import com.digit.tenant.migration.MigrationController;
import com.digit.tenant.migration.MigrationService;
import com.digit.tenant.migration.TenantMigrationConfig;
import com.digit.tenant.migration.TenantTransactionFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Wires tenant schema separation: the per-request transaction + search_path filter, the migration
 * service (schema creation + per-tenant Flyway) and the /internal/migrate endpoint. Mirrors the Go
 * main.go tenant-migration setup and tenantdb.GinMiddleware.
 *
 * <p>The Go service additionally validates required headers (X-Tenant-ID, X-User-ID) via its own
 * {@code middleware.Headers}, which runs before the tenantdb middleware — reproduced here by the
 * {@link HeadersFilter} (order 35, before the tenant transaction filter at 40).
 */
@Configuration
@Import(MigrationController.class)
public class TenantConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    @Value("${spring.datasource.username}")
    private String dbUser;
    @Value("${spring.datasource.password}")
    private String dbPassword;
    @Value("${MIGRATION_ENABLED:true}")
    private boolean migrationEnabled;

    @Bean
    public TenantMigrationConfig tenantMigrationConfig(EmployeeProperties props) {
        TenantMigrationConfig cfg = new TenantMigrationConfig();
        cfg.setEnabled(migrationEnabled);
        cfg.setSchemaSeparationMode(TenantMigrationConfig.schemaSeparationModeFromEnv());
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setFlywayUser(dbUser);
        cfg.setFlywayPassword(dbPassword);
        cfg.setFlywayLocations(props.getTenantMigration().getFlywayLocations());
        cfg.setSchemaTable(props.getTenantMigration().getSchemaTable());
        return cfg;
    }

    @Bean
    public MigrationService migrationService(TenantMigrationConfig cfg) {
        return new MigrationService(cfg);
    }

    @Bean
    public FilterRegistrationBean<HeadersFilter> headersFilter(EmployeeProperties props,
                                                               ObjectMapper objectMapper) {
        HeadersFilter filter = new HeadersFilter(props.getServer().getContextPath(), objectMapper);
        FilterRegistrationBean<HeadersFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(35); // after observability filters, before the tenant transaction filter (40)
        return reg;
    }

    @Bean
    public FilterRegistrationBean<TenantTransactionFilter> tenantTransactionFilter(
            DataSource dataSource, PlatformTransactionManager txManager) {
        TenantTransactionFilter filter = new TenantTransactionFilter(
                dataSource, txManager, "X-Tenant-ID",
                TenantMigrationConfig.schemaSeparationModeFromEnv());
        FilterRegistrationBean<TenantTransactionFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(40);
        return reg;
    }
}
