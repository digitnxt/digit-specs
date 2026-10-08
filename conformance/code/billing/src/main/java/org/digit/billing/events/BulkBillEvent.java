package org.digit.billing.events;

import org.digit.billing.model.BillRequests.BulkBillGenerationJob;

/** Consumed envelope shape (matches BillingEventPublisher / the Go publisher). */
public record BulkBillEvent(String eventType, long eventTime, String tenantId, String userId,
                            String traceId, BulkBillGenerationJob data) {
}
