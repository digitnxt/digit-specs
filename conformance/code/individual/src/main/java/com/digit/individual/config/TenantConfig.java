package com.digit.individual.config;

import com.digit.tenant.migration.MigrationController;
import com.digit.tenant.migration.MigrationService;
import com.digit.tenant.migration.TenantMigrationConfig;
import com.digit.tenant.migration.TenantTransactionFilter;
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
    // MIGRATION_ENABLED defaults true (Go tenantmigration.ConfigFromEnv).
    @Value("${MIGRATION_ENABLED:true}")
    private boolean migrationEnabled;

    @Bean
    public TenantMigrationConfig tenantMigrationConfig(IndividualProperties props) {
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
    public FilterRegistrationBean<TenantTransactionFilter> tenantTransactionFilter(
            DataSource dataSource, PlatformTransactionManager txManager) {
        TenantTransactionFilter filter = new TenantTransactionFilter(
                dataSource, txManager, "X-Tenant-ID",
                TenantMigrationConfig.schemaSeparationModeFromEnv());
        FilterRegistrationBean<TenantTransactionFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(40); // after forward-headers/tracing/metrics/header-validation, mirroring Go middleware order
        return reg;
    }
}
