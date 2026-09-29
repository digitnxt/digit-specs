package com.digit.boundary.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * boundary-specific business metrics. Mirrors Go pkg/observability/business_metrics.go
 * (boundaries_created_total, boundaries_searched_total, boundaries_updated_total).
 *
 * <p>Backed by the Micrometer {@link MeterRegistry} provided by Spring Boot Actuator, so these
 * counters surface alongside http_server_requests / db_operations_total on /actuator/metrics +
 * /actuator/prometheus.
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordBoundaryCreated(String tenantId, int count) {
        Counter.builder("boundaries_created_total")
                .description("Total number of boundaries created")
                .tag("tenantId", tenantId)
                .tag("operation", "create")
                .register(registry)
                .increment(count);
    }

    public void recordBoundarySearched(String tenantId, int resultCount) {
        Counter.builder("boundaries_searched_total")
                .description("Total number of boundary searches performed")
                .tag("tenantId", tenantId)
                .tag("operation", "search")
                .register(registry)
                .increment();
    }

    public void recordBoundaryUpdated(String tenantId, int count) {
        Counter.builder("boundaries_updated_total")
                .description("Total number of boundaries updated")
                .tag("tenantId", tenantId)
                .tag("operation", "update")
                .register(registry)
                .increment(count);
    }

    /**
     * DB operation counter. The old tracer's ObservabilityMetrics.recordDbOperation provided this;
     * the restructured tracer (3.0.0-SNAPSHOT-5) dropped it, so the metric is kept alive here with
     * the same name and tags (same approach as the filestore service).
     */
    public void recordDbOperation(String operation, String table, boolean success) {
        Counter.builder("db_operations_total")
                .description("Total number of database operations")
                .tag("operation", operation == null ? "" : operation)
                .tag("table", table == null ? "" : table)
                .tag("success", Boolean.toString(success))
                .register(registry)
                .increment();
    }
}
