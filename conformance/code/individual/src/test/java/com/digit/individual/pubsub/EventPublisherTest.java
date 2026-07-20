package com.digit.individual.pubsub;

import com.digit.individual.config.IndividualProperties;
import org.digit.tracer.pubsub.PubSubClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the event envelope carries the standard fields — including {@code traceId} (#15) — and
 * that publishing honours both the client presence and the {@code pubsub.enabled} flag (#6).
 */
class EventPublisherTest {

    /** Captures the last published event map. */
    static class CapturingClient implements PubSubClient {
        Map<String, Object> lastEvent;
        String lastTopic;
        int publishCount;

        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override @SuppressWarnings("unchecked")
        public void publish(String topic, Object event) {
            this.lastTopic = topic;
            this.lastEvent = (Map<String, Object>) event;
            this.publishCount++;
        }
        @Override public void subscribe(String t, String g, java.util.function.Consumer<byte[]> c) {}
        @Override public void unsubscribe(String t, String g) {}
    }

    private static IndividualProperties props(boolean pubsubEnabled) {
        IndividualProperties p = new IndividualProperties();
        p.getPubsub().setEnabled(pubsubEnabled);
        return p;
    }

    @Test
    void event_includes_traceId_and_standard_fields() {
        CapturingClient client = new CapturingClient();
        EventPublisher publisher = new EventPublisher(client, props(true));

        publisher.publishEvent("individual-create-individual", "CREATE", "t1", "c1",
                new HashMap<>(), 1);

        assertEquals(1, client.publishCount);
        assertEquals("individual-create-individual", client.lastTopic);
        Map<String, Object> e = client.lastEvent;
        // traceId key is always present (empty string when there is no active span, as in Go).
        assertTrue(e.containsKey("traceId"), "event must carry a traceId key");
        assertEquals("", e.get("traceId"));
        assertEquals("CREATE", e.get("eventType"));
        assertEquals("t1", e.get("tenantId"));
        assertEquals("c1", e.get("clientId"));
        assertTrue(e.containsKey("eventTime"));
        assertTrue(e.containsKey("data"));
    }

    @Test
    void publishing_is_suppressed_when_pubsub_disabled() {
        CapturingClient client = new CapturingClient();
        EventPublisher publisher = new EventPublisher(client, props(false));

        publisher.publishEvent("topic", "CREATE", "t1", "c1", new HashMap<>(), 1);

        assertEquals(0, client.publishCount);
        assertNull(client.lastEvent);
    }
}
