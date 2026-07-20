package com.digit.employee.pubsub;

import com.digit.employee.config.EmployeeProperties;
import com.digit.tenant.migration.MigrationService;
import org.digit.tracer.pubsub.PubSubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes tenant-migration events and triggers schema creation/migration.
 * Mirrors Go internal/pubsub/migration_consumer.go (works with both Kafka and Redis via tracer/pubsub).
 *
 * <p>Subscribes via the official tracer {@link PubSubClient}. When no client is configured
 * (digit.tracer.pubsub.type unset) consumption is a graceful no-op and manual migration via the
 * /internal/migrate API is still available.
 */
@Component
public class MigrationConsumer {

    private static final Logger log = LoggerFactory.getLogger(MigrationConsumer.class);

    private final PubSubClient pubSubClient; // may be null
    private final EmployeeProperties props;
    private final MigrationService migrationService;

    @Autowired
    public MigrationConsumer(@Autowired(required = false) PubSubClient pubSubClient,
                             EmployeeProperties props,
                             MigrationService migrationService) {
        this.pubSubClient = pubSubClient;
        this.props = props;
        this.migrationService = migrationService;
    }

    /** Starts the consumer in the background once the app is ready (Go: go consumer.StartWithRetry). */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread t = new Thread(this::startWithRetry, "migration-consumer");
        t.setDaemon(true);
        t.start();
    }

    /** Begins consuming migration events. Mirrors Go Start gating. */
    public void start() throws Exception {
        if (!props.getTenantMigration().isEnabled()) {
            log.info("Migration consumer disabled: schema separation not enabled");
            return;
        }
        if (pubSubClient == null) {
            log.info("Migration consumer disabled: PubSub not available "
                    + "(use /internal/migrate API for manual migrations)");
            return;
        }
        if (migrationService == null) {
            log.info("Migration consumer disabled: migration service not initialized");
            return;
        }
        String topic = props.getTenantMigration().getTopic();
        String consumerGroup = "kafka".equals(props.getPubsub().getType())
                ? props.getPubsub().getKafka().getConsumerGroup()
                : props.getPubsub().getRedis().getConsumerGroup();
        log.info("Starting migration consumer on topic: {} (type: {})", topic, props.getPubsub().getType());
        pubSubClient.subscribe(topic, consumerGroup, this::handleMigrationEvent);
    }

    /** Retries Start on failure, mirroring Go StartWithRetry. */
    public void startWithRetry() {
        while (true) {
            try {
                start();
                return;
            } catch (Exception e) {
                log.error("Migration consumer error, retrying in 2 seconds", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleMigrationEvent(byte[] payload) {
        if (payload == null) {
            return;
        }
        log.info("[MIGRATION_EVENT] Received event: {}", new String(payload, StandardCharsets.UTF_8));
        try {
            migrationService.handleMessage(payload);
        } catch (Exception e) {
            log.error("Failed to handle migration event, payload={}",
                    new String(payload, StandardCharsets.UTF_8), e);
        }
    }
}
