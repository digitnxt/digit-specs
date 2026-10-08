package org.digit.billing.events;

import java.util.function.Consumer;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.service.BillService;
import org.digit.tracer.config.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * bulk-bill-generator consumer. On processing failure the job goes to the DLQ
 * topic and the message is acknowledged (no tracer retry — matching Go's
 * consume-once flow); only a failed DLQ publish escapes to the tracer's
 * retry/dead-letter pipeline.
 */
@Component
public class BulkBillJobHandler implements Consumer<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(BulkBillJobHandler.class);

    private final JsonMapper mapper;
    private final BillService billService;
    private final BillingEventPublisher publisher;
    private final BillingProperties properties;

    public BulkBillJobHandler(ObjectMapperFactory mapperFactory, BillService billService,
                              BillingEventPublisher publisher, BillingProperties properties) {
        this.mapper = mapperFactory.getObjectMapper();
        this.billService = billService;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public void accept(byte[] payload) {
        BulkBillEvent event = mapper.readValue(payload, BulkBillEvent.class);
        if (!"BULK_BILL_GENERATION".equals(event.eventType())) {
            return;
        }
        try {
            billService.processBulkBillJob(event.data());
        } catch (RuntimeException e) {
            log.error("Bulk bill job failed, routing to DLQ: jobID={}", event.data().id(), e);
            publisher.publish(properties.topics().bulkBillGenerationDlq(), "BULK_BILL_GENERATION_DLQ",
                    event.tenantId(), event.userId(), event.data());
        }
    }
}
