package org.digit.billing.events;

import java.util.function.Consumer;
import org.digit.billing.service.BillService;
import org.digit.tracer.config.ObjectMapperFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLQ consumer: retries the failed job per-consumer. Q1 fix: filters on the
 * DLQ eventType the DLQ publisher actually sends (the Go filter made this
 * path dead code). Throwing (all consumers failed) hands the message to the
 * tracer's retry/dead-letter pipeline.
 */
@Component
public class BulkBillDlqHandler implements Consumer<byte[]> {

    private final JsonMapper mapper;
    private final BillService billService;

    public BulkBillDlqHandler(ObjectMapperFactory mapperFactory, BillService billService) {
        this.mapper = mapperFactory.getObjectMapper();
        this.billService = billService;
    }

    @Override
    public void accept(byte[] payload) {
        BulkBillEvent event = mapper.readValue(payload, BulkBillEvent.class);
        if (!"BULK_BILL_GENERATION_DLQ".equals(event.eventType())) {
            return;
        }
        billService.retryBulkJobPerConsumer(event.data());
    }
}
