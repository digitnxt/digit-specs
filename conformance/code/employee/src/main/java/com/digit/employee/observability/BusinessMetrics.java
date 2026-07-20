package com.digit.employee.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * employee-service-specific business metrics. Mirrors Go pkg/observability/business_metrics.go.
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

    private void inc(String name, String description, String tenantId, String operation, double amount) {
        Counter.builder(name)
                .description(description)
                .tag("tenantId", tenantId)
                .tag("operation", operation)
                .register(registry)
                .increment(amount);
    }

    public void recordEmployeeCreated(String tenantId, int count) {
        inc("employees_created_total", "Total number of employees created", tenantId, "create", count);
    }

    public void recordEmployeeSearched(String tenantId, int resultCount) {
        inc("employees_searched_total", "Total number of employee searches performed", tenantId, "search", 1);
    }

    public void recordEmployeeUpdated(String tenantId, int count) {
        inc("employees_updated_total", "Total number of employees updated", tenantId, "update", count);
    }

    public void recordEmployeeDeleted(String tenantId) {
        inc("employees_deleted_total", "Total number of employees deleted", tenantId, "delete", 1);
    }

    public void recordEmployeeDeactivated(String tenantId) {
        inc("employees_deactivated_total", "Total number of employees deactivated", tenantId, "deactivate", 1);
    }

    public void recordEmployeeReactivated(String tenantId) {
        inc("employees_reactivated_total", "Total number of employees reactivated", tenantId, "reactivate", 1);
    }

    public void recordJurisdictionCreated(String tenantId) {
        inc("jurisdictions_created_total", "Total number of jurisdictions created", tenantId, "create", 1);
    }

    public void recordJurisdictionSearched(String tenantId, int resultCount) {
        inc("jurisdictions_searched_total", "Total number of jurisdiction searches performed", tenantId, "search", 1);
    }

    public void recordJurisdictionUpdated(String tenantId) {
        inc("jurisdictions_updated_total", "Total number of jurisdictions updated", tenantId, "update", 1);
    }
}
