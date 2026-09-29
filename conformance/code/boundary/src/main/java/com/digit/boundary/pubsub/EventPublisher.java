package com.digit.boundary.pubsub;

import com.digit.boundary.config.BoundaryProperties;
import io.opentelemetry.api.trace.Span;
import org.digit.tracer.pubsub.PubSubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes domain events. Mirrors Go internal/pubsub/event_publisher.go: builds a standard event
 * envelope ({eventType, eventTime, tenantId, userId, traceId, data}) and publishes it; failures are
 * logged but never fail the request (graceful degradation).
 *
 * <p>The {@link PubSubClient} is supplied by the official tracer auto-configuration and is only
 * present when {@code tracer.pubsub.type} is set. When it is absent (Kafka not configured)
 * publishing is a graceful no-op, matching the Go service.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final PubSubClient pubSubClient; // may be null when pub/sub disabled or unavailable
    private final BoundaryProperties props;

    @Autowired
    public EventPublisher(@Autowired(required = false) PubSubClient pubSubClient, BoundaryProperties props) {
        this.pubSubClient = pubSubClient;
        this.props = props;
    }

    public void publishEvent(String topic, String eventType, String tenantId, String userId,
                             Object data, int count) {
        // Go shouldPublish (event_publisher.go): pubsubClient != nil && config.PubSub.Enabled —
        // pubsub.enabled=false must suppress publishing even when a client bean exists.
        if (pubSubClient == null || !props.getPubsub().isEnabled()) {
            return; // graceful no-op, like Go when pub/sub is unavailable or disabled
        }

        String traceId = "";
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            traceId = span.getSpanContext().getTraceId();
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("eventTime", System.currentTimeMillis());
        event.put("tenantId", tenantId);
        event.put("userId", userId);
        event.put("traceId", traceId);
        event.put("data", data);

        try {
            pubSubClient.publish(topic, event);
        } catch (Exception e) {
            log.warn("Failed to publish {} event to topic={} tenantId={}: {}",
                    eventType, topic, tenantId, e.getMessage());
            return; // graceful degradation, like Go
        }

        log.info("Published {} event to topic={} tenantId={} count={}", eventType, topic, tenantId, count);
    }
}
