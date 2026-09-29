package org.digit.notify.app.domain.repository;

import com.digit.tenant.migration.service.MigrationService;
import org.digit.notify.app.dispatch.DispatchEngine;
import org.digit.notify.app.domain.entity.ProviderMappingEntity;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.spi.Channel;
import org.digit.notify.spi.Recipient;
import com.digit.tenant.migration.web.TenantContext;
import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.digit.notify.app.domain.entity.ProviderEntity;
import org.digit.notify.app.domain.entity.config.ChannelConfig;
import org.digit.notify.app.domain.entity.config.ChannelsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What schema separation actually has to deliver for this service, against a real Postgres: tenant
 * data is invisible across tenants, and the provider registry is not tenant data.
 *
 * <p>Separate from {@link RepositoryIntegrationTest} because it needs the feature switched on, and
 * that changes the DataSource for the whole context — with it off the search_path wrapper is never
 * installed, so an unqualified {@code provider} would pass there and fail in a deployment.
 */
@SpringBootTest(properties = {
    "notify.plugins.directory=./providers",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "digit.tenant-migration.enabled=true",
    "digit.tenant-migration.schema-table=notify_schema"
})
@Testcontainers
class TenantSchemaRoutingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("notify")
        .withUsername("notify")
        .withPassword("notify");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    MigrationService migrationService;

    @Autowired
    NotificationConfigRepository configRepository;

    @Autowired
    ProviderRepository providerRepository;

    @Autowired
    ProviderMappingRepository mappingRepository;

    @Autowired
    DispatchEngine dispatchEngine;

    private static NotificationConfigEntity config(String tenantId, String templateCode) {
        var sms = new ChannelConfig();
        sms.setEnabled(true);
        sms.setBody(Map.of("default", "Hello"));
        var channels = new ChannelsConfig();
        channels.setSms(sms);

        var entity = new NotificationConfigEntity();
        entity.setTenantId(tenantId);
        entity.setTemplateCode(templateCode);
        entity.setActive(true);
        entity.setChannels(channels);
        entity.getAuditDetail().setCreatedTime(Instant.now());
        return entity;
    }

    /**
     * Dispatch fans the channels out across virtual threads, and TenantContext is a plain
     * ThreadLocal that a forked task does not inherit. Left uncarried, every query inside dispatch
     * runs at the shared schema: the tenant's own provider_mapping row is invisible and the channel
     * comes back FAILED with "No provider mapping found" — for a mapping the API lists quite
     * happily, because that read never leaves the request thread.
     *
     * <p>Only reproducible against a real database with separation on, which is why it lives here
     * rather than in DispatchEngineTest, where the repository is a mock and every thread sees the
     * same answer.
     *
     * <p>No provider jars are loaded in a test, so the send cannot succeed. That is what makes the
     * assertion precise: reaching "no active providers" proves the mapping was found, and is a
     * different failure from not finding it at all.
     */
    @Test
    void dispatchSeesTheTenantsProviderMappingFromItsForkedThreads() throws Exception {
        migrationService.migrateTenant("dz");

        try (var scope = TenantContext.open("dz")) {
            var mapping = new ProviderMappingEntity();
            mapping.setTenantId("dz");
            mapping.setChannel("SMS");
            mapping.setProviders(List.of("smscountry"));
            mapping.getAuditDetail().setCreatedTime(Instant.now());
            mappingRepository.save(mapping);
        }

        var cfg = config("dz", "routing-send");
        var request = new NotifyRequest("routing-send",
            new Recipient("+911234567890", null, List.of(), null, Map.of()),
            Map.of(), null, Map.of());

        org.digit.notify.app.dispatch.DispatchOutcome outcome;
        try (var scope = TenantContext.open("dz")) {
            outcome = dispatchEngine.dispatch(request, cfg, "dz");
        }

        var sms = outcome.results().stream()
            .filter(r -> r.channel() == Channel.SMS).findFirst().orElseThrow();
        assertThat(sms.reason()).doesNotContain("No provider mapping found");
        assertThat(sms.reason()).contains("No active providers");
    }

    @Test
    void aConfigWrittenUnderOneTenantIsNotVisibleUnderAnother() throws Exception {
        migrationService.migrateTenant("pb");
        migrationService.migrateTenant("ka");

        try (var scope = TenantContext.open("pb")) {
            configRepository.save(config("pb", "OTP_LOGIN"));
        }

        try (var scope = TenantContext.open("pb")) {
            assertThat(configRepository.findByTenantIdAndTemplateCode("pb", "OTP_LOGIN")).isPresent();
        }
        // Same row, same query, different schema. The tenant_id column would have excluded it too —
        // this asserts the row is not in ka's table at all, which is the separation doing the work.
        try (var scope = TenantContext.open("ka")) {
            assertThat(configRepository.findByTenantIdAndTemplateCode("pb", "OTP_LOGIN")).isEmpty();
        }
    }

    /**
     * ProviderRegistrar writes the registry at startup with no tenant bound. If provider reads
     * followed search_path, every tenant would see an empty registry and every provider-mapping
     * create would fail validation with "Provider not found in registry".
     */
    @Test
    void theProviderRegistryIsVisibleFromInsideEveryTenantSchema() throws Exception {
        migrationService.migrateTenant("pb");

        var provider = new ProviderEntity();
        provider.setProviderName("routing-test-provider");
        provider.setChannels(List.of("EMAIL"));
        provider.setActive(true);
        provider.getAuditDetail().setCreatedTime(Instant.now());
        providerRepository.save(provider);        // nothing bound, as at startup

        try (var scope = TenantContext.open("pb")) {
            assertThat(providerRepository.findByProviderName("routing-test-provider")).isPresent();
        }
    }
}
