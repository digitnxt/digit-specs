package org.digit.billing.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.digit.tracer.pubsub.PubSubClient;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Publishes the Go event envelope {eventType, eventTime, tenantId, userId,
 * traceId, data}. Q7: publish failures PROPAGATE (tracer EVENT_BUS_FAILURE) —
 * a business event that doesn't land must not pretend it did.
 */
@Component
public class BillingEventPublisher {

    private final PubSubClient pubSub;

    public BillingEventPublisher(PubSubClient pubSub) {
        this.pubSub = pubSub;
    }

    public void publish(String topic, String eventType, String tenantId, String userId, Object data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("eventTime", System.currentTimeMillis());
        event.put("tenantId", tenantId);
        event.put("userId", userId);
        event.put("traceId", Objects.toString(MDC.get("traceId"), ""));
        event.put("data", data);
        pubSub.publish(topic, event);
    }
}
