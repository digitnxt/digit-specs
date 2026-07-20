package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

// OpenTelemetryConfig holds OpenTelemetry related configuration
type OpenTelemetryConfig struct {
	ServiceName    string
	ServiceVersion string
	OTLPEndpoint   string
	SamplingRatio  float64
	Enabled        bool
	MetricsEnabled bool
	PrometheusPort string
}

// LoggingConfig holds logging related configuration
type LoggingConfig struct {
	Level              string
	ConsoleLogsEnabled bool
}

// Config holds all configuration for the application
type Config struct {
	Server          ServerConfig
	Database        DatabaseConfig
	IDGen           IDGenConfig
	Boundary        BoundaryConfig
	Individual      IndividualConfig
	Keycloak        KeycloakConfig
	PubSub          PubSubConfig
	TenantMigration TenantMigrationConfig
	OpenTelemetry   OpenTelemetryConfig
	Logging         LoggingConfig
}

// ServerConfig holds server-related configuration
type ServerConfig struct {
	Port        string
	ContextPath string
}

// DatabaseConfig holds database related configuration
type DatabaseConfig struct {
	DBHost     string
	DBPort     string
	DBUser     string
	DBPassword string
	DBName     string
	SSLMode    string
}

// IDGenConfig holds configuration for the ID generation service
type IDGenConfig struct {
	Host      string `mapstructure:"host"`
	Path      string `mapstructure:"path"`
	IDGenName string `mapstructure:"idgen_name"`
	Enabled   bool   `mapstructure:"enabled"`
}

type BoundaryConfig struct {
	BaseURL string `mapstructure:"base_url"`
	// Path is the full path of the boundary relationship endpoint
	// (e.g. "/boundary/v3/relationship"). The client appends only the
	// querystring — no path concatenation happens in code, so deployments
	// can re-route or version this endpoint without a code change.
	Path    string `mapstructure:"path"`
	Enabled bool   `mapstructure:"enabled"`
}

type IndividualConfig struct {
	Host string `mapstructure:"host"`
	// Path is the full path to the individuals collection (e.g.
	// "/individuals/v3/individuals"). The client appends "/{individualID}"
	// to form the lookup URL.
	Path    string `mapstructure:"path"`
	Enabled bool   `mapstructure:"enabled"`
}

// KeycloakConfig intentionally has no Path field — the admin-user lookup
// path (`/admin/realms/{realm}/users/{userID}`) is part of the Keycloak
// Admin REST API spec, not something we control or vary per deployment.
// It stays hardcoded in the client.
type KeycloakConfig struct {
	BaseURL string `mapstructure:"base_url"`
	Enabled bool   `mapstructure:"enabled"`
}

// TenantMigrationConfig contains all tenant migration related configuration
type TenantMigrationConfig struct {
	Enabled           bool   // Enable/disable schema separation (true = per-tenant schema, false = public schema)
	Topic             string // Topic for tenant creation events that trigger schema migration
	FlywayLocations   string // Flyway migration locations (e.g., filesystem:services/employee/db/migrations)
	MigrateScriptPath string // Path to migrate.sh script
	FlywayBinPath     string // Path to flyway binary
	SchemaTable       string // Flyway schema history table name (e.g., employee_schema)
}

type PubSubConfig struct {
	Enabled bool
	Type    string // "kafka" or "redis"
	Topics  PubSubTopics
	Kafka   KafkaConfig
	Redis   RedisConfig
}

type PubSubTopics struct {
	CreateEmployee     string
	UpdateEmployee     string
	DeleteEmployee     string
	CreateJurisdiction string
	UpdateJurisdiction string
}

type KafkaConfig struct {
	Brokers       string
	AutoCreate    bool
	Partitions    int
	Replication   int
	ConsumerGroup string
}

type RedisConfig struct {
	Address         string
	Password        string
	DB              int
	ConsumerGroup   string
	ConsumerID      string
	RetentionDays   int
	MaxStreamLength int64
	CleanupInterval time.Duration
}

// LoadConfig loads configuration from environment variables with sensible defaults
func LoadConfig() (*Config, error) {
	cfg := &Config{
		Server: ServerConfig{
			Port:        getEnv("SERVER_PORT", "8080"),
			ContextPath: getEnv("SERVER_CONTEXT_PATH", "/employee"),
		},
		Database: DatabaseConfig{
			DBHost:     getEnv("DB_HOST", "localhost"),
			DBPort:     getEnv("DB_PORT", "5434"),
			DBUser:     getEnv("DB_USER", "postgres"),
			DBPassword: getEnv("DB_PASSWORD", "password"),
			DBName:     getEnv("DB_NAME", "postgres"),
			SSLMode:    getEnv("DB_SSL_MODE", "disable"),
		},
		IDGen: IDGenConfig{
			Host: getEnv("IDGEN_HOST", "http://localhost:8100"),
			// Path must point at the generate endpoint, not the API root.
			// idgen service exposes POST /idgen/v3/generate; the client
			// concatenates host + path directly, so the leaf must be here.
			Path:      getEnv("IDGEN_PATH", "/idgen/v3/generate"),
			IDGenName: getEnv("IDGEN_NAME", "EmployeeCode"),
			Enabled:   getEnvAsBool("IDGEN_ENABLED", true),
		},
		Boundary: BoundaryConfig{
			BaseURL: getEnv("BOUNDARY_HOST", "http://localhost:8095"),
			Path:    getEnv("BOUNDARY_PATH", "/boundary/v3/relationship"),
			Enabled: getEnvAsBool("BOUNDARY_ENABLED", true),
		},
		Individual: IndividualConfig{
			Host:    getEnv("INDIVIDUAL_HOST", "http://localhost:8086"),
			Path:    getEnv("INDIVIDUAL_PATH", "/individuals/v3/individuals"),
			Enabled: getEnvAsBool("INDIVIDUAL_ENABLED", false),
		},
		Keycloak: KeycloakConfig{
			BaseURL: getEnv("KEYCLOAK_BASE_URL", "https://digit-lts.digit.org/keycloak"),
			Enabled: getEnvAsBool("KEYCLOAK_ENABLED", false),
		},
		PubSub: PubSubConfig{
			Enabled: getEnvAsBool("PUBSUB_ENABLED", false),
			Type:    getEnv("PUBSUB_TYPE", "kafka"),
			Topics: PubSubTopics{
				CreateEmployee:     getEnv("PUBSUB_TOPIC_CREATE_EMPLOYEE", "employee-create-employee"),
				UpdateEmployee:     getEnv("PUBSUB_TOPIC_UPDATE_EMPLOYEE", "employee-update-employee"),
				DeleteEmployee:     getEnv("PUBSUB_TOPIC_DELETE_EMPLOYEE", "employee-delete-employee"),
				CreateJurisdiction: getEnv("PUBSUB_TOPIC_CREATE_JURISDICTION", "employee-create-jurisdiction"),
				UpdateJurisdiction: getEnv("PUBSUB_TOPIC_UPDATE_JURISDICTION", "employee-update-jurisdiction"),
			},
			Kafka: KafkaConfig{
				Brokers:       getEnv("KAFKA_BROKERS", "localhost:9092"),
				AutoCreate:    getEnvAsBool("KAFKA_AUTO_CREATE_TOPICS_ENABLE", true),
				Partitions:    getEnvAsInt("KAFKA_PARTITION_COUNT", 1),
				Replication:   getEnvAsInt("KAFKA_REPLICATION_FACTOR", 1),
				ConsumerGroup: getEnv("KAFKA_CONSUMER_GROUP", "employee-service"),
			},
			Redis: RedisConfig{
				Address:         getEnv("REDIS_ADDRESS", "localhost:6379"),
				Password:        getEnv("REDIS_PASSWORD", ""),
				DB:              getEnvAsInt("REDIS_DB", 0),
				ConsumerGroup:   getEnv("REDIS_CONSUMER_GROUP", "employee-service"),
				ConsumerID:      getEnv("REDIS_CONSUMER_ID", "employee-service-1"),
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
			SchemaTable:       getEnv("MIGRATION_SCHEMA_TABLE", "employee_schema"),
		},
		OpenTelemetry: OpenTelemetryConfig{
			ServiceName:    getEnv("OTEL_SERVICE_NAME", "employee-service"),
			ServiceVersion: getEnv("OTEL_SERVICE_VERSION", "1.0.0"),
			OTLPEndpoint:   getEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:4320"),
			SamplingRatio:  getEnvAsFloat64("OTEL_TRACES_SAMPLER_ARG", 1.0),
			Enabled:        getEnvAsBool("OTEL_ENABLED", false),
			MetricsEnabled: getEnvAsBool("OTEL_METRICS_ENABLED", false),
			PrometheusPort: getEnv("PROMETHEUS_PORT", "9090"),
		},
		Logging: LoggingConfig{
			Level:              getEnv("LOG_LEVEL", "info"),
			ConsoleLogsEnabled: getEnvAsBool("CONSOLE_LOGS_ENABLED", true),
		},
	}

	return cfg, nil
}

// Helper functions matching style of other services
func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func getEnvAsInt(key string, defaultVal int) int {
	if val := os.Getenv(key); val != "" {
		if i, err := strconv.Atoi(val); err == nil {
			return i
		}
	}
	return defaultVal
}

func getEnvAsBool(key string, defaultVal bool) bool {
	if val := os.Getenv(key); val != "" {
		lowered := strings.ToLower(val)
		return lowered == "1" || lowered == "true" || lowered == "yes"
	}
	return defaultVal
}

func getEnvAsFloat64(key string, defaultVal float64) float64 {
	if val := os.Getenv(key); val != "" {
		if f, err := strconv.ParseFloat(val, 64); err == nil {
			return f
		}
	}
	return defaultVal
}
