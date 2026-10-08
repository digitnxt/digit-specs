package org.digit.billing.events;

import org.digit.billing.config.BillingProperties;
import org.digit.tracer.pubsub.PubSubClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Subscription manifest (tracer README pattern). Consumer group = the Go one. */
@Component
public class PubSubSubscriptions implements ApplicationRunner {

    private static final String GROUP = "billing-service";

    private final PubSubClient pubSub;
    private final BillingProperties properties;
    private final BulkBillJobHandler jobHandler;
    private final BulkBillDlqHandler dlqHandler;

    public PubSubSubscriptions(PubSubClient pubSub, BillingProperties properties,
                               BulkBillJobHandler jobHandler, BulkBillDlqHandler dlqHandler) {
        this.pubSub = pubSub;
        this.properties = properties;
        this.jobHandler = jobHandler;
        this.dlqHandler = dlqHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        pubSub.subscribe(properties.topics().bulkBillGeneration(), GROUP, jobHandler);
        pubSub.subscribe(properties.topics().bulkBillGenerationDlq(), GROUP, dlqHandler);
    }
}
