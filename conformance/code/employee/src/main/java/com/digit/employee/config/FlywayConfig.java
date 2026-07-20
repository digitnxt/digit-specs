package com.digit.employee.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Runs Flyway migrations against the default (public) schema at startup. Spring Boot 4 ships
 * auto-configuration in per-technology modules; rather than depend on that wiring we own Flyway
 * explicitly here (the same Flyway tool the Go service used via its migrate.sh/init-container, and
 * the same engine tenant-migration-java uses per tenant). Keeps Flyway as the single migration tool.
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource, EmployeeProperties props) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(props.getTenantMigration().getFlywayLocations())
                .table(props.getTenantMigration().getSchemaTable())
                .schemas("public")
                .defaultSchema("public")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .outOfOrder(true)
                .validateOnMigrate(true)
                .load();
    }
}
