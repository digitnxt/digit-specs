package org.digit.billing.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters (port of pkg/observability/business_metrics.go). Names use
 * Micrometer dots — Prometheus renders bills_generated_total etc., matching Go.
 */
@Component
public class BillingMetrics {

    private final MeterRegistry registry;

    public BillingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private Counter counter(String name, String tenantId, String businessServiceCode, String operation) {
        Counter.Builder builder = Counter.builder(name)
                .tag("tenantId", tenantId)
                .tag("operation", operation);
        if (businessServiceCode != null) {
            builder = builder.tag("business_service_code", businessServiceCode);
        }
        return builder.register(registry);
    }

    public void billGenerated(String tenantId, String businessServiceCode) {
        counter("bills.generated", tenantId, businessServiceCode, "generate").increment();
    }

    public void billCancelled(String tenantId, String businessServiceCode) {
        counter("bills.cancelled", tenantId, businessServiceCode, "cancel").increment();
    }

    public void demandCreated(String tenantId, String businessServiceCode) {
        counter("demands.created", tenantId, businessServiceCode, "create").increment();
    }

    public void demandUpdated(String tenantId, String businessServiceCode) {
        counter("demands.updated", tenantId, businessServiceCode, "update").increment();
    }

    public void demandCancelled(String tenantId) {
        counter("demands.cancelled", tenantId, null, "cancel").increment();
    }

    public void demandFrozen(String tenantId) {
        counter("demands.frozen", tenantId, null, "freeze").increment();
    }

    public void paymentCreated(String tenantId) {
        counter("payments.created", tenantId, null, "create").increment();
    }

    public void businessServicesCreated(String tenantId, int count) {
        counter("business.services.created", tenantId, null, "create").increment(count);
    }

    public void taxHeadsCreated(String tenantId, int count) {
        counter("tax.heads.created", tenantId, null, "create").increment(count);
    }
}
