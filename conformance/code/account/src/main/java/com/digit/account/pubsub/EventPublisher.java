package com.digit.account.pubsub;

import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.digit.tracer.pubsub.PubSubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publishes domain events + raw migration events. Mirrors Go internal/pubsub/event_publisher.go:
 * builds the standard envelope and publishes; failures are logged but never fail the request.
 *
 * <p>The {@link PubSubClient} is supplied by the official tracer auto-configuration and is only
 * present when {@code digit.tracer.pubsub.type} is set. When it is absent (Kafka/Redis not
 * configured) publishing is a graceful no-op, matching the Go service.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final PubSubClient pubSubClient; // may be null
    private final AccountProperties props;
    private final ObjectMapper objectMapper;

    @Autowired
    public EventPublisher(@Autowired(required = false) PubSubClient pubSubClient,
                          AccountProperties props, ObjectMapper objectMapper) {
        this.pubSubClient = pubSubClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Mirrors PublishEvent — envelope keys: eventType, eventTime, tenantId, userId, traceId, data. */
    public void publishEvent(String topic, String eventType, String tenantId, String userId,
                             Object data, int count) {
        if (!shouldPublish()) {
            return;
        }
        String traceId = "";
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            traceId = span.getSpanContext().getTraceId();
        }
        Map<String, Object> event = new HashMap<>();
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
            return; // graceful degradation
        }
        log.info("Published {} event to topic={} tenantId={} count={}", eventType, topic, tenantId, count);
    }

    /** Mirrors PublishRaw — publishes pre-serialized JSON bytes (e.g. migration events). */
    public void publishRaw(String topic, byte[] payload) {
        if (!shouldPublish()) {
            return;
        }
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            log.error("Failed to unmarshal raw payload for topic={}", topic, e);
            throw new RuntimeException(e);
        }
        try {
            pubSubClient.publish(topic, event);
        } catch (Exception e) {
            log.error("Failed to publish raw event to topic={}", topic, e);
            throw new RuntimeException(e);
        }
    }

    private boolean shouldPublish() {
        return pubSubClient != null && props.getPubsub().isEnabled();
    }
}
