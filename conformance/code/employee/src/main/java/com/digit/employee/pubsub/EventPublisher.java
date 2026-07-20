package com.digit.employee.pubsub;

import com.digit.employee.config.EmployeeProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.digit.tracer.pubsub.PubSubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publishes domain events. Mirrors Go internal/pubsub/event_publisher.go: builds a standard event
 * envelope and publishes it; failures are logged but never fail the request (graceful degradation).
 *
 * <p>The {@link PubSubClient} is supplied by the official tracer auto-configuration and is only
 * present when {@code digit.tracer.pubsub.type} is set. When it is absent (no broker configured)
 * publishing is a graceful no-op, matching the Go service.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final PubSubClient pubSubClient; // may be null when pub/sub disabled or unavailable
    private final EmployeeProperties props;

    @Autowired
    public EventPublisher(@Autowired(required = false) PubSubClient pubSubClient, EmployeeProperties props) {
        this.pubSubClient = pubSubClient;
        this.props = props;
    }

    public void publishEvent(String topic, String eventType, String tenantId, String clientId,
                             Object data, int count) {
        // Mirrors Go shouldPublish(): needs both a client AND the pubsub.enabled flag.
        if (pubSubClient == null || !props.getPubsub().isEnabled()) {
            return; // graceful no-op, like Go when pub/sub is unavailable or disabled
        }

        // traceId from the active OpenTelemetry span (empty when none), matching Go's event envelope.
        String traceId = "";
        SpanContext sc = Span.current().getSpanContext();
        if (sc.isValid()) {
            traceId = sc.getTraceId();
        }

        Map<String, Object> event = new HashMap<>();
        event.put("eventType", eventType);
        event.put("eventTime", System.currentTimeMillis());
        event.put("tenantId", tenantId);
        event.put("clientId", clientId);
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
