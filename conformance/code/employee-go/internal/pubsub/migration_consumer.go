package pubsub

import (
	"context"
	"employee/internal/config"
	"encoding/json"
	"log"
	"time"

	tenantmigration "github.com/digitnxt/digit3/src/libraries/tenant-migration"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	tracerpubsub "github.com/digitnxt/digit3/src/libraries/tracer/pubsub"
)

// MigrationConsumer consumes migration events and triggers schema creation
// Supports both Kafka and Redis Streams via tracer/pubsub
type MigrationConsumer struct {
	pubsubClient tracerpubsub.PubSubClient
	config       *config.Config
	logger       *tracerobs.OTelLogger
	migrationSvc *tenantmigration.Service
}

// NewMigrationConsumer creates a new migration consumer
func NewMigrationConsumer(
	pubsubClient tracerpubsub.PubSubClient,
	cfg *config.Config,
	migrationSvc *tenantmigration.Service,
) *MigrationConsumer {
	return &MigrationConsumer{
		pubsubClient: pubsubClient,
		config:       cfg,
		logger:       tracerobs.GetOTelLogger(),
		migrationSvc: migrationSvc,
	}
}

// Start begins consuming migration events
// Works with both Kafka and Redis Streams (configured via tracer/pubsub)
func (mc *MigrationConsumer) Start(ctx context.Context) error {
	// Migration consumer requires:
	// 1. Schema separation enabled
	// 2. PubSub client available (Kafka or Redis)
	// 3. Migration service initialized
	// Note: Migration service can work without PubSub via /internal/migrate API
	if !mc.config.TenantMigration.Enabled {
		log.Printf("Migration consumer disabled: schema separation not enabled")
		return nil
	}

	if mc.pubsubClient == nil {
		log.Printf("Migration consumer disabled: PubSub not available (use /internal/migrate API for manual migrations)")
		return nil
	}

	if mc.migrationSvc == nil {
		log.Printf("Migration consumer disabled: migration service not initialized")
		return nil
	}

	pubsubType := mc.config.PubSub.Type
	if pubsubType == "" {
		pubsubType = "kafka"
	}

	log.Printf("Starting migration consumer on topic: %s (type: %s)", mc.config.TenantMigration.Topic, pubsubType)

	// Subscribe to migration events
	// tracer/pubsub handles both Kafka and Redis Streams transparently
	callback := func(message interface{}) {
		mc.handleMigrationEvent(ctx, message)
	}

	return mc.pubsubClient.Subscribe(mc.config.TenantMigration.Topic, callback)
}

// handleMigrationEvent processes a migration event
func (mc *MigrationConsumer) handleMigrationEvent(ctx context.Context, message interface{}) {
	// Message from Redis is a string (the raw JSON from the stream)
	// Convert it to bytes for HandleMessage
	var payload []byte

	switch v := message.(type) {
	case string:
		// Redis Streams: message is already a JSON string
		payload = []byte(v)
	case []byte:
		// Kafka: message might be bytes
		payload = v
	default:
		// Fallback: marshal the message
		var err error
		payload, err = json.Marshal(message)
		if err != nil {
			mc.logger.ErrorWithTrace(ctx, err, "Failed to marshal migration event", nil)
			return
		}
	}

	log.Printf("[MIGRATION_EVENT] Received event: %s", string(payload))

	// Handle the migration event
	if err := mc.migrationSvc.HandleMessage(ctx, payload); err != nil {
		mc.logger.ErrorWithTrace(ctx, err, "Failed to handle migration event", map[string]interface{}{
			"payload": string(payload),
		})
	}
}

// StartWithRetry starts the consumer with retry logic
// Automatically reconnects on failure
func (mc *MigrationConsumer) StartWithRetry(ctx context.Context) {
	for {
		err := mc.Start(ctx)
		if err == nil || err == context.Canceled {
			return
		}

		mc.logger.ErrorWithTrace(ctx, err, "Migration consumer error, retrying in 2 seconds", nil)
		time.Sleep(2 * time.Second)
	}
}
