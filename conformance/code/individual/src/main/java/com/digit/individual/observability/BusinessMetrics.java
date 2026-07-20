package com.digit.individual.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Individual-specific business metrics. Mirrors Go pkg/observability/business_metrics.go.
 *
 * <p>Backed by the Micrometer {@link MeterRegistry} provided by Spring Boot Actuator (the same
 * registry the official tracer's ObservabilityMetrics uses), so these counters surface alongside the
 * tracer's http_server_requests / db_operations metrics on /actuator/metrics + /actuator/prometheus.
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordIndividualCreated(String tenantId, int count) {
        Counter.builder("individuals_created_total")
                .description("Total number of individuals created")
                .tag("tenantId", tenantId)
                .tag("operation", "create")
                .register(registry)
                .increment(count);
    }

    public void recordIndividualSearched(String tenantId, int resultCount) {
        Counter.builder("individuals_searched_total")
                .description("Total number of individual searches performed")
                .tag("tenantId", tenantId)
                .tag("operation", "search")
                .register(registry)
                .increment();
    }

    public void recordIndividualUpdated(String tenantId, int count) {
        Counter.builder("individuals_updated_total")
                .description("Total number of individuals updated")
                .tag("tenantId", tenantId)
                .tag("operation", "update")
                .register(registry)
                .increment(count);
    }

    public void recordIndividualDeleted(String tenantId, int count) {
        Counter.builder("individuals_deleted_total")
                .description("Total number of individuals deleted (soft)")
                .tag("tenantId", tenantId)
                .tag("operation", "delete")
                .register(registry)
                .increment(count);
    }

    public void recordConfigUpserted(String tenantId, boolean created) {
        Counter.builder("configs_upserted_total")
                .description("Total number of tenant configs upserted")
                .tag("tenantId", tenantId)
                .tag("operation", created ? "create" : "update")
                .register(registry)
                .increment();
    }
}
