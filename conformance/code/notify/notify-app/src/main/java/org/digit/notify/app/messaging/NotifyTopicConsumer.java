package org.digit.notify.app.messaging;

import com.digit.tenant.migration.service.MigrationService;
import com.digit.tenant.migration.validators.TenantIds;
import com.digit.tenant.migration.web.TenantContext;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.digit.notify.app.controller.dto.NotifyMessageDto;
import org.digit.notify.app.controller.mapper.NotifyMapper;
import org.digit.notify.app.service.NotificationService;
import org.digit.tracer.pubsub.PubSubClient;
import org.digit.tracer.pubsub.Subscription;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.stream.Collectors;

/**
 * Consumes sends from the notify topic and runs them through the same service call the API uses, so
 * the two entry points cannot drift: a topic message and a {@code POST /v3/notifications} with the
 * same body produce the same dispatch, the same log row and the same attempt rows.
 *
 * <p>The difference is what the caller gets back. The API returns the per-channel outcome; a topic
 * caller has no response to read, so the outcome is logged and {@code notification_attempt} is the
 * record of it.
 *
 * <p>Subscribes through the tracer's {@link PubSubClient}, which is Kafka or Redis depending on
 * {@code tracer.pubsub.type}, on a daemon thread started at application-ready and retried until the
 * broker accepts it — a broker that is slow to come up delays consumption rather than failing
 * startup. Same arrangement tenant-migration's consumer uses.
 */
@Component
public class NotifyTopicConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotifyTopicConsumer.class);
    private static final long INITIAL_RETRY_MS = 2_000;
    private static final long MAX_RETRY_MS = 60_000;

    private final @Nullable PubSubClient pubSubClient;
    private final NotificationService notificationService;
    private final MigrationService migrationService;
    private final NotifyMapper mapper;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final boolean enabled;
    private final String topic;
    private final String consumerGroup;

    private volatile @Nullable Subscription subscription;

    public NotifyTopicConsumer(
        @Nullable PubSubClient pubSubClient,
        NotificationService notificationService,
        MigrationService migrationService,
        NotifyMapper mapper,
        ObjectMapper objectMapper,
        Validator validator,
        @Value("${notify.topics.send.enabled:false}") boolean enabled,
        @Value("${notify.topics.send.topic:notify-send}") String topic,
        @Value("${notify.topics.send.consumer-group:notify-service}") String consumerGroup
    ) {
        this.pubSubClient = pubSubClient;
        this.notificationService = notificationService;
        this.migrationService = migrationService;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.enabled = enabled;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
    }

    /**
     * Off unless switched on, so adding the consumer does not change how an existing deployment
     * behaves. Subscribing at application-ready rather than during bean creation keeps a slow broker
     * from failing startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("Notify topic consumer disabled (notify.topics.send.enabled=false)");
            return;
        }
        if (pubSubClient == null) {
            log.warn("Notify topic consumer enabled but no PubSubClient is configured; "
                + "POST /v3/notifications is unaffected");
            return;
        }
        Thread t = new Thread(this::subscribeWithRetry, "notify-topic-consumer");
        t.setDaemon(true);
        t.start();
    }

    private void subscribeWithRetry() {
        long backoff = INITIAL_RETRY_MS;
        while (true) {
            try {
                subscription = pubSubClient.subscribe(topic, consumerGroup, this::handleMessage);
                log.info("Subscribed to notify topic {} as {}", topic, consumerGroup);
                return;
            } catch (RuntimeException e) {
                log.warn("Could not subscribe to {} ({}); retrying in {}ms", topic, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_RETRY_MS);
            }
        }
    }

    /**
     * Everything is allowed to throw. The tracer's container retries with backoff, dead-letters the
     * record once retries are exhausted and only then commits the offset, so a message that cannot
     * be handled ends up in the error pipeline rather than being dropped — and a poison record does
     * not block the partition. Catching here would replace that with a log line and a lost send.
     */
    void handleMessage(byte[] payload) {
        NotifyMessageDto message;
        try {
            message = objectMapper.readValue(payload, NotifyMessageDto.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable notify message: " + e.getMessage(), e);
        }

        var violations = validator.validate(message);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("invalid notify message: " + violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; ")));
        }

        String tenantId = message.tenantId();

        // Nothing has bound the tenant on this thread: TenantContextFilter only runs for requests, so
        // without this the connection routes at the shared schema and the send looks up its config
        // in the wrong place — a 404 for a template the tenant definitely has. Validated the way the
        // filter validates the header, because the value ends up in SET search_path either way, and a
        // message is no more trustworthy than a request. With separation off there is no per-tenant
        // schema to bind, so the shared one is the right answer rather than a fallback.
        String schema = migrationService.isEnabled()
            ? TenantIds.validate(tenantId)
            : TenantContext.PUBLIC_SCHEMA;

        try (var scope = TenantContext.open(tenantId, schema)) {
            // The same call the controller makes, with the tenant from the message instead of the header.
            var response = notificationService.sendNotification(
                mapper.toDomain(message.request()), tenantId);

            // A topic caller has no response to read, so the per-channel outcome is logged. Note a send
            // where every channel failed still returns normally, exactly as it does over HTTP, and is
            // recorded in notification_attempt rather than retried here.
            log.info("Notify message dispatched: notificationId={} templateCode={} channels={}",
                response.notificationId(), response.templateCode(),
                response.channels().stream()
                    .map(c -> c.channel() + "=" + c.status())
                    .collect(Collectors.joining(",")));
        }
    }

    @PreDestroy
    public void stop() {
        Subscription s = subscription;
        if (s != null) {
            s.close();
        }
    }
}