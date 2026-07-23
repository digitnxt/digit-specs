package com.digit.accesscontrol.config;

import com.digit.tenant.migration.MigrationController;
import com.digit.tenant.migration.MigrationService;
import com.digit.tenant.migration.TenantMigrationConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the tenant-migration service + the {@code /internal/migrate} endpoint for parity with the
 * platform's tenant-onboarding flow.
 *
 * <p>NOTE: unlike most services, accesscontrol is a <b>shared-schema</b> service — tenant scoping
 * is by the {@code tenant_id} column, not by a per-tenant Postgres schema (see the Go README:
 * "accesscontrol is a shared-schema service, no per-tenant Flyway migrations"). The Go service uses
 * GORM auto-commit per statement and enforces the X-Tenant-ID / X-User-ID headers inside its own gin
 * middleware (with custom error envelopes and internal routes that take no tenant header). The
 * shared {@code TenantTransactionFilter} is therefore intentionally NOT registered here: its
 * blanket header enforcement and {@code {"error":...}} envelope would break the internal Kong
 * endpoints and the AccessControl error contract. Header validation is performed in the controllers
 * exactly as the Go middleware did.
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
    public TenantMigrationConfig tenantMigrationConfig(AccessControlProperties props) {
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
}
