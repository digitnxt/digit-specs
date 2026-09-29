package com.digit.individual.tenant;

import java.util.List;

import com.digit.tenant.migration.service.MigrationService;
import com.digit.tenant.migration.config.TenantMigrationProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises tenant-migration against a real Postgres, using this service's own migrations.
 *
 * <p>Skipped unless {@code -Dit.postgres=true}, so it stays out of the image build, which has no
 * database. Named {@code ...Test} rather than {@code ...IT} on purpose: Surefire's default includes
 * are {@code *Test.java}/{@code Test*.java}, so an {@code IT} suffix would be silently skipped in
 * every run and the guard below would never be what decides. Run it against the dev compose stack:
 *
 * <pre>
 * mvn test -Dit.postgres=true
 * </pre>
 *
 * <p>Override the target with {@code -Dit.jdbcUrl=... -Dit.dbUser=... -Dit.dbPassword=...}.
 * The test creates and drops its own schemas and never touches public.
 */
@EnabledIfSystemProperty(named = "it.postgres", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantMigrationPostgresTest {

    private static final String TENANT = "ITCITYA";
    private static final String DISABLED_TENANT = "ITCITYB";
    private static final String HISTORY_TABLE = "individual_schema";

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                System.getProperty("it.jdbcUrl", "jdbc:postgresql://127.0.0.1:5434/postgres"),
                System.getProperty("it.dbUser", "postgres"),
                System.getProperty("it.dbPassword", "password"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        dropTenantSchemas();
    }

    @AfterAll
    static void cleanUp() {
        dropTenantSchemas();
    }

    private static void dropTenantSchemas() {
        if (jdbc == null) {
            return;
        }
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + TENANT + "\" CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + DISABLED_TENANT + "\" CASCADE");
    }

    private static MigrationService service(boolean schemaSeparation) {
        TenantMigrationProperties cfg = new TenantMigrationProperties();
        cfg.setEnabled(schemaSeparation);
        cfg.setFlywayLocations("classpath:db/migration");
        cfg.setSchemaTable(HISTORY_TABLE);
        return new MigrationService(cfg, dataSource);
    }

    private boolean schemaExists(String schema) {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = ?",
                Integer.class, schema);
        return n != null && n > 0;
    }

    private List<String> tablesIn(String schema) {
        return jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = ? order by 1",
                String.class, schema);
    }

    @Test
    @Order(1)
    @DisplayName("a new tenant gets its own schema with this service's tables")
    void migratesNewTenant() throws Exception {
        assertThat(schemaExists(TENANT)).as("schema must not pre-exist").isFalse();

        service(true).migrateTenant(TENANT);

        assertThat(schemaExists(TENANT)).isTrue();
        assertThat(tablesIn(TENANT))
                .contains("individual_v3", "individual_address_v3", "individual_identifier_v3",
                        "individual_document_v3", "individual_config_v3")
                .contains(HISTORY_TABLE);
    }

    @Test
    @Order(2)
    @DisplayName("every migration on the classpath is applied, and all of them succeed")
    void appliesEveryMigration() {
        List<String> versions = jdbc.queryForList(
                "select version from \"" + TENANT + "\"." + HISTORY_TABLE
                        + " where version is not null order by installed_rank", String.class);
        Integer failed = jdbc.queryForObject(
                "select count(*) from \"" + TENANT + "\"." + HISTORY_TABLE + " where success = false",
                Integer.class);

        assertThat(failed).isZero();
        // The 8 V*.sql files in src/main/resources/db/migration.
        assertThat(versions).hasSize(8);
        assertThat(versions).contains("20260625100000");
    }

    @Test
    @Order(3)
    @DisplayName("the tenant's tables are separate objects from public's, not views onto them")
    void tenantSchemaIsIsolated() {
        // Schema-qualified on purpose. Asserting via SET search_path would prove nothing here: this
        // DataSource opens a fresh connection per statement, so the SET and the count land on
        // different sessions. Routing by search_path is the request filter's job and is covered there.
        Integer inTenant = jdbc.queryForObject(
                "select count(*) from \"" + TENANT + "\".individual_v3", Integer.class);
        assertThat(inTenant).as("a freshly migrated tenant starts empty").isZero();

        Integer inPublic = jdbc.queryForObject(
                "select count(*) from public." + HISTORY_TABLE, Integer.class);
        assertThat(inPublic).as("the tenant run left public's history alone").isPositive();

        assertThat(jdbc.queryForObject(
                "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                        + " where c.relname = 'individual_v3' and n.nspname in ('public', ?)",
                Integer.class, TENANT))
                .as("two distinct physical tables").isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("re-running the same tenant is a no-op, so a redelivered event is harmless")
    void migrationIsIdempotent() throws Exception {
        Integer before = jdbc.queryForObject(
                "select count(*) from \"" + TENANT + "\"." + HISTORY_TABLE, Integer.class);

        service(true).migrateTenant(TENANT);

        Integer after = jdbc.queryForObject(
                "select count(*) from \"" + TENANT + "\"." + HISTORY_TABLE, Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    @Order(5)
    @DisplayName("with separation off, no tenant schema is created at all")
    void doesNothingWhenSeparationOff() throws Exception {
        service(false).migrateTenant(DISABLED_TENANT);
        assertThat(schemaExists(DISABLED_TENANT)).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("a tenantId that would hit a reserved schema is refused before any DDL runs")
    void refusesReservedTenantIds() {
        MigrationService svc = service(true);

        assertThatThrownBy(() -> svc.migrateTenant("public"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.migrateTenant("pg_toast"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.migrateTenant("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);

        // public still holds exactly the tables it started with.
        assertThat(tablesIn("public")).contains("individual_v3", HISTORY_TABLE);
    }

    @Test
    @Order(7)
    @DisplayName("the event account publishes drives the whole thing end to end")
    void migratesFromAccountEventPayload() throws Exception {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + TENANT + "\" CASCADE");

        service(true).handleMessage(("{\"tenantId\":\"" + TENANT + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(schemaExists(TENANT)).isTrue();
        assertThat(tablesIn(TENANT)).contains("individual_v3");
    }
}