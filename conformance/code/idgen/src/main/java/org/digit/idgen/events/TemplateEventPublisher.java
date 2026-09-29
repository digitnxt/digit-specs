package org.digit.idgen.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.digit.idgen.config.IdgenProperties;
import org.digit.tracer.pubsub.PubSubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Template lifecycle events. Fire-and-forget by contract (as in Go): the tracer's
 * publish() throws on broker failure, but a lifecycle event must never fail the
 * request that produced it — failures are logged and swallowed.
 */
@Component
public class TemplateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TemplateEventPublisher.class);

    private final PubSubClient pubSub;
    private final IdgenProperties.Events events;

    public TemplateEventPublisher(PubSubClient pubSub, IdgenProperties properties) {
        this.pubSub = pubSub;
        this.events = properties.events();
    }

    public void created(String tenantId, String userId, Object data) {
        publish(events.topics().create(), "CREATE", tenantId, userId, data);
    }

    public void updated(String tenantId, String userId, Object data) {
        publish(events.topics().update(), "UPDATE", tenantId, userId, data);
    }

    public void deleted(String tenantId, Object data) {
        publish(events.topics().delete(), "DELETE", tenantId, "", data);
    }

    private void publish(String topic, String eventType, String tenantId, String clientId, Object data) {
        if (!events.enabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("eventTime", Instant.now().toEpochMilli());
        event.put("tenantId", tenantId);
        event.put("clientId", clientId);
        event.put("traceId", Objects.toString(MDC.get("traceId"), ""));
        event.put("data", data);
        try {
            pubSub.publish(topic, event);
        } catch (RuntimeException e) {
            log.error("Failed to publish {} event to {} — continuing (fire-and-forget)", eventType, topic, e);
        }
    }
}
