package pubsub

import (
	"context"
	"individual/internal/config"
	"strings"

	tracerconfig "github.com/digitnxt/digit3/src/libraries/tracer/config"
	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	tracerpubsub "github.com/digitnxt/digit3/src/libraries/tracer/pubsub"
)

// NewPubSubClient initializes the PubSub client based on configuration
// Uses the tracer library with proper configuration
// Returns nil if PubSub is disabled or configuration is invalid
func NewPubSubClient(cfg *config.Config, logger *tracerobs.OTelLogger) tracerpubsub.PubSubClient {
	if !cfg.PubSub.Enabled {
		logger.InfoWithTrace(context.Background(), "PubSub disabled", nil)
		return nil
	}

	// Validate PubSub type
	if cfg.PubSub.Type != "kafka" && cfg.PubSub.Type != "redis" {
		logger.ErrorWithTrace(context.Background(), nil, "Invalid PubSub type - must be 'kafka' or 'redis'", map[string]interface{}{
			"type": cfg.PubSub.Type,
		})
		return nil
	}

	// Build tracer config based on pubsub type
	var pubsubConfig tracerconfig.PubSubConfig

	switch cfg.PubSub.Type {
	case "kafka":
		// Parse broker addresses
		brokers := []string{}
		for _, broker := range strings.Split(cfg.PubSub.Kafka.Brokers, ",") {
			if broker = strings.TrimSpace(broker); broker != "" {
				brokers = append(brokers, broker)
			}
		}

		// Validate brokers are not empty
		if len(brokers) == 0 {
			logger.ErrorWithTrace(context.Background(), nil, "Kafka brokers not configured - KAFKA_BROKERS environment variable is required", nil)
			return nil
		}

		// Validate partition and replication settings
		if cfg.PubSub.Kafka.Partitions <= 0 {
			logger.ErrorWithTrace(context.Background(), nil, "Invalid Kafka partitions - must be > 0", map[string]interface{}{
				"partitions": cfg.PubSub.Kafka.Partitions,
			})
			return nil
		}

		if cfg.PubSub.Kafka.Replication <= 0 {
			logger.ErrorWithTrace(context.Background(), nil, "Invalid Kafka replication factor - must be > 0", map[string]interface{}{
				"replication": cfg.PubSub.Kafka.Replication,
			})
			return nil
		}

		pubsubConfig = tracerconfig.PubSubConfig{
			Type: "kafka",
			KafkaConfig: &tracerconfig.KafkaConnectionConfig{
				Brokers:       brokers,
				AutoCreate:    cfg.PubSub.Kafka.AutoCreate,
				Partitions:    cfg.PubSub.Kafka.Partitions,
				Replication:   cfg.PubSub.Kafka.Replication,
				ConsumerGroup: cfg.PubSub.Kafka.ConsumerGroup,
			},
		}

		logger.InfoWithTrace(context.Background(), "Kafka PubSub configuration validated", map[string]interface{}{
			"brokers":     len(brokers),
			"partitions":  cfg.PubSub.Kafka.Partitions,
			"replication": cfg.PubSub.Kafka.Replication,
			"auto_create": cfg.PubSub.Kafka.AutoCreate,
		})

	case "redis":
		// Validate Redis address is not empty
		if strings.TrimSpace(cfg.PubSub.Redis.Address) == "" {
			logger.ErrorWithTrace(context.Background(), nil, "Redis address not configured - PUBSUB_REDIS_ADDR environment variable is required", nil)
			return nil
		}

		// Validate consumer group is not empty
		if strings.TrimSpace(cfg.PubSub.Redis.ConsumerGroup) == "" {
			logger.ErrorWithTrace(context.Background(), nil, "Redis consumer group not configured - REDIS_CONSUMER_GROUP environment variable is required", nil)
			return nil
		}

		pubsubConfig = tracerconfig.PubSubConfig{
			Type: "redis",
			RedisConfig: &tracerconfig.RedisConnectionConfig{
				Address:         cfg.PubSub.Redis.Address,
				Password:        cfg.PubSub.Redis.Password,
				DB:              cfg.PubSub.Redis.DB,
				ConsumerGroup:   cfg.PubSub.Redis.ConsumerGroup,
				ConsumerID:      cfg.PubSub.Redis.ConsumerID,
				RetentionDays:   cfg.PubSub.Redis.RetentionDays,
				MaxStreamLength: cfg.PubSub.Redis.MaxStreamLength,
				CleanupInterval: cfg.PubSub.Redis.CleanupInterval,
			},
		}

		logger.InfoWithTrace(context.Background(), "Redis PubSub configuration validated", map[string]interface{}{
			"address":          cfg.PubSub.Redis.Address,
			"consumer_group":   cfg.PubSub.Redis.ConsumerGroup,
			"retention_days":   cfg.PubSub.Redis.RetentionDays,
			"max_stream_len":   cfg.PubSub.Redis.MaxStreamLength,
			"cleanup_interval": cfg.PubSub.Redis.CleanupInterval.String(),
		})
	}

	// Create client using tracer library factory
	// This validates configuration and applies defaults
	pubsubClient, err := tracerpubsub.NewPubSubClient(pubsubConfig)
	if err != nil {
		logger.ErrorWithTrace(context.Background(), err, "Failed to create PubSub client - service will continue without pub/sub", map[string]interface{}{
			"type": cfg.PubSub.Type,
		})
		return nil
	}

	logger.InfoWithTrace(context.Background(), "PubSub client created successfully", map[string]interface{}{
		"type": cfg.PubSub.Type,
	})

	// NOTE: Connect() is called in main.go after client creation
	// This allows proper error handling and logging at startup

	return pubsubClient
}
