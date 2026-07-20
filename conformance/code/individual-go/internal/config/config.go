package config

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/joho/godotenv"
	"github.com/rs/zerolog/log"
)

// Config holds all application configuration
type Config struct {
	Server          ServerConfig
	Database        DatabaseConfig
	Vault           VaultConfig
	IDGen           IDGenConfig
	Logging         LoggingConfig
	PubSub          PubSubConfig
	TenantMigration TenantMigrationConfig
	OpenTelemetry   OpenTelemetryConfig

	// HMACSecret is the pepper for the mobile-number blind index (HMAC-SHA256). It must be
	// set (and stable) in any deployment that stores real data; no default is provided so a
	// missing secret is caught at startup rather than silently weakening the hash.
	HMACSecret string
}

// OpenTelemetryConfig holds OpenTelemetry configuration
type OpenTelemetryConfig struct {
	ServiceName    string
	ServiceVersion string
	OTLPEndpoint   string
	Protocol       string
	SamplingRatio  float64
	Enabled        bool
	MetricsEnabled bool
	PrometheusPort string
}

// ServerConfig holds server configuration
type ServerConfig struct {
	Port        string
	ContextPath string
	GinMode     string
	Timezone    string
}

// DatabaseConfig holds database configuration
type DatabaseConfig struct {
	DBHost          string
	DBPort          string
	DBUser          string
	DBPassword      string
	DBName          string
	SSLMode         string
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime int
}

// VaultConfig holds Vault configuration
type VaultConfig struct {
	Address  string
	RoleID   string
	SecretID string
	Enabled  bool
}

// IDGenConfig holds ID generation service configuration
type IDGenConfig struct {
	Host    string
	Path    string
	Enabled bool
	Format  string
}

// LoggingConfig holds logging configuration
type LoggingConfig struct {
	Level              string
	Format             string
	ConsoleLogsEnabled bool
}

// TenantMigrationConfig contains all tenant migration related configuration
type TenantMigrationConfig struct {
	Enabled           bool   // Enable/disable schema separation (true = per-tenant schema, false = public schema)
	Topic             string // Topic for tenant creation events that trigger schema migration
	FlywayLocations   string // Flyway migration locations (e.g., filesystem:services/individual/db/migrations)
	MigrateScriptPath string // Path to migrate.sh script
	FlywayBinPath     string // Path to flyway binary
	SchemaTable       string // Flyway schema history table name (e.g., individual_schema)
}

// PubSubConfig holds PubSub configuration
type PubSubConfig struct {
	Enabled bool
	Type    string // "kafka" or "redis"
	Topics  PubSubTopics
	Kafka   KafkaConfig
	Redis   PubSubRedisConfig
}

// PubSubTopics holds topic names for events
type PubSubTopics struct {
	CreateIndividual string
	UpdateIndividual string
	DeleteIndividual string
	UpsertConfig     string
}

// KafkaConfig holds Kafka configuration
type KafkaConfig struct {
	Brokers       string
	AutoCreate    bool
	Partitions    int
	Replication   int
	ConsumerGroup string
}

// PubSubRedisConfig holds Redis configuration for PubSub
type PubSubRedisConfig struct {
	Address         string
	Password        string
	DB              int
	ConsumerGroup   string
	ConsumerID      string
	RetentionDays   int
	MaxStreamLength int64
	CleanupInterval time.Duration
}

// LoadConfig loads configuration from environment variables
func LoadConfig() (*Config, error) {
	// Load .env file if it exists
	_ = godotenv.Load()

	config := &Config{
		Server: ServerConfig{
			Port:        getEnv("SERVER_PORT", "8080"),
			ContextPath: getEnv("SERVER_CONTEXT_PATH", "/individuals"),
			GinMode:     getEnv("GIN_MODE", "debug"),
			Timezone:    getEnv("APP_TIMEZONE", "UTC"),
		},
		Database: DatabaseConfig{
			DBHost:          getEnv("DB_HOST", "localhost"),
			DBPort:          getEnv("DB_PORT", "5434"),
			DBUser:          getEnv("DB_USER", "postgres"),
			DBPassword:      getEnv("DB_PASSWORD", "password"),
			DBName:          getEnv("DB_NAME", "postgres"),
			SSLMode:         getEnv("DB_SSL_MODE", "disable"),
			MaxOpenConns:    getEnvAsInt("DB_MAX_OPEN_CONNS", 25),
			MaxIdleConns:    getEnvAsInt("DB_MAX_IDLE_CONNS", 5),
			ConnMaxLifetime: getEnvAsInt("DB_CONN_MAX_LIFETIME", 300),
		},
		Vault: VaultConfig{
			Address:  getEnv("VAULT_HOST", "http://localhost:8202"),
			RoleID:   getEnv("VAULT_ROLE_ID", ""),
			SecretID: getEnv("VAULT_SECRET_ID", ""),
			Enabled:  getEnvAsBool("VAULT_ENABLED", false),
		},
		IDGen: IDGenConfig{
			Host:    getEnv("IDGEN_HOST", "http://localhost:8100"),
			Path:    getEnv("IDGEN_PATH", "/idgen/v3/generate"),
			Enabled: getEnvAsBool("IDGEN_ENABLED", true),
			Format:  getEnv("IDGEN_INDIVIDUAL_ID_FORMAT", "individual"),
		},
		Logging: LoggingConfig{
			Level:              getEnv("LOG_LEVEL", "info"),
			Format:             getEnv("LOG_FORMAT", "json"),
			ConsoleLogsEnabled: getEnvAsBool("CONSOLE_LOGS_ENABLED", true),
		},
		PubSub: PubSubConfig{
			Enabled: getEnvAsBool("PUBSUB_ENABLED", false),
			Type:    getEnv("PUBSUB_TYPE", "kafka"),
			Topics: PubSubTopics{
				CreateIndividual: getEnv("PUBSUB_TOPIC_CREATE_INDIVIDUAL", "individual-create-individual"),
				UpdateIndividual: getEnv("PUBSUB_TOPIC_UPDATE_INDIVIDUAL", "individual-update-individual"),
				DeleteIndividual: getEnv("PUBSUB_TOPIC_DELETE_INDIVIDUAL", "individual-delete-individual"),
				UpsertConfig:     getEnv("PUBSUB_TOPIC_UPSERT_CONFIG", "individual-upsert-config"),
			},
			Kafka: KafkaConfig{
				Brokers:       getEnv("KAFKA_BROKERS", "localhost:9092"),
				AutoCreate:    getEnvAsBool("KAFKA_AUTO_CREATE_TOPICS_ENABLE", true),
				Partitions:    getEnvAsInt("KAFKA_PARTITION_COUNT", 1),
				Replication:   getEnvAsInt("KAFKA_REPLICATION_FACTOR", 1),
				ConsumerGroup: getEnv("KAFKA_CONSUMER_GROUP", "individual-service"),
			},
			Redis: PubSubRedisConfig{
				Address:         getEnv("REDIS_ADDRESS", "localhost:6379"),
				Password:        getEnv("REDIS_PASSWORD", ""),
				DB:              getEnvAsInt("REDIS_DB", 0),
				ConsumerGroup:   getEnv("REDIS_CONSUMER_GROUP", "individual-service"),
				ConsumerID:      getEnv("REDIS_CONSUMER_ID", "individual-service-1"),
				RetentionDays:   getEnvAsInt("REDIS_RETENTION_DAYS", 7),
				MaxStreamLength: int64(getEnvAsInt("REDIS_MAX_STREAM_LENGTH", 1000000)),
				CleanupInterval: time.Duration(getEnvAsInt("REDIS_CLEANUP_INTERVAL", 3600)) * time.Second,
			},
		},
		TenantMigration: TenantMigrationConfig{
			Enabled:           getEnvAsBool("SCHEMA_SEPARATION_MODE", false),
			Topic:             getEnv("SCHEMA_SEPARATION_TOPIC", "account-migration"),
			FlywayLocations:   getEnv("MIGRATION_FLYWAY_LOCATIONS", "filesystem:.db/migrations"),
			MigrateScriptPath: getEnv("MIGRATION_SCRIPT_PATH", ".db/migrate.sh"),
			FlywayBinPath:     getEnv("MIGRATION_FLYWAY_BIN", "flyway"),
			SchemaTable:       getEnv("MIGRATION_SCHEMA_TABLE", "individual_schema"),
		},
		OpenTelemetry: OpenTelemetryConfig{
			ServiceName:    getEnv("OTEL_SERVICE_NAME", "individual-service"),
			ServiceVersion: getEnv("OTEL_SERVICE_VERSION", "1.0.0"),
			OTLPEndpoint:   getEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318"),
			Protocol:       getEnv("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf"),
			SamplingRatio:  getEnvAsFloat64("OTEL_SAMPLING_RATIO", 1.0),
			Enabled:        getEnvAsBool("OTEL_ENABLED", false),
			MetricsEnabled: getEnvAsBool("OTEL_METRICS_ENABLED", false),
			PrometheusPort: getEnv("PROMETHEUS_PORT", ""),
		},
		HMACSecret: getEnv("HMAC_SECRET", ""),
	}

	if err := config.Validate(); err != nil {
		return nil, err
	}

	return config, nil
}

// Validate validates the configuration
func (c *Config) Validate() error {
	if c.Server.Port == "" {
		return fmt.Errorf("SERVER_PORT is required")
	}

	if c.Database.DBHost == "" {
		return fmt.Errorf("DB_HOST is required")
	}

	if c.Database.DBName == "" {
		return fmt.Errorf("DB_NAME is required")
	}

	// Fail closed: when Vault is enabled the mobile column is encrypted at rest, so the
	// blind index MUST be keyed — otherwise the (reversible) hash would defeat the encryption.
	// A blank pepper here is a deploy misconfiguration, not something to silently accept.
	if c.Vault.Enabled && c.HMACSecret == "" {
		return fmt.Errorf("HMAC_SECRET is required when Vault is enabled (mobile-number blind index must be keyed)")
	}

	return nil
}

// Helper functions

func getEnv(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	return value
}

func getEnvAsInt(key string, defaultValue int) int {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		return defaultValue
	}

	value, err := strconv.Atoi(valueStr)
	if err != nil {
		log.Warn().Err(err).Str("key", key).Str("value", valueStr).Int("default", defaultValue).Msg("invalid integer env var, using default")
		return defaultValue
	}

	return value
}

func getEnvAsFloat64(key string, defaultValue float64) float64 {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		return defaultValue
	}

	value, err := strconv.ParseFloat(valueStr, 64)
	if err != nil {
		log.Warn().Err(err).Str("key", key).Str("value", valueStr).Float64("default", defaultValue).Msg("invalid float64 env var, using default")
		return defaultValue
	}

	return value
}

func getEnvAsBool(key string, defaultValue bool) bool {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		return defaultValue
	}

	value, err := strconv.ParseBool(valueStr)
	if err != nil {
		log.Warn().Err(err).Str("key", key).Str("value", valueStr).Bool("default", defaultValue).Msg("invalid boolean env var, using default")
		return defaultValue
	}

	return value
}
