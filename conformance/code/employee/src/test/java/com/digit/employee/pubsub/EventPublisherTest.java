package com.digit.employee.pubsub;

import com.digit.employee.config.EmployeeProperties;
import org.digit.tracer.pubsub.PubSubClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice G: event envelope carries traceId (#7) and publishing honours the pubsub.enabled flag. */
class EventPublisherTest {

    static class CapturingClient implements PubSubClient {
        Map<String, Object> lastEvent;
        int publishCount;
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override @SuppressWarnings("unchecked")
        public void publish(String topic, Object event) { lastEvent = (Map<String, Object>) event; publishCount++; }
        @Override public void subscribe(String t, String g, java.util.function.Consumer<byte[]> c) {}
        @Override public void unsubscribe(String t, String g) {}
    }

    private static EmployeeProperties props(boolean pubsubEnabled) {
        EmployeeProperties p = new EmployeeProperties();
        p.getPubsub().setEnabled(pubsubEnabled);
        return p;
    }

    @Test
    void event_carriesTraceId_key() {
        CapturingClient client = new CapturingClient();
        new EventPublisher(client, props(true))
                .publishEvent("t", "CREATE", "t1", "c1", new HashMap<>(), 1);
        assertEquals(1, client.publishCount);
        assertTrue(client.lastEvent.containsKey("traceId"));
    }

    @Test
    void disabled_suppressesPublish() {
        CapturingClient client = new CapturingClient();
        new EventPublisher(client, props(false))
                .publishEvent("t", "CREATE", "t1", "c1", new HashMap<>(), 1);
        assertEquals(0, client.publishCount);
        assertNull(client.lastEvent);
    }
}
