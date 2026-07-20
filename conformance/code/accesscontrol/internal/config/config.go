package config

import (
	"errors"
	"os"
	"strconv"
	"time"

	"github.com/joho/godotenv"
	"github.com/rs/zerolog/log"
)

// Config holds the application configuration
type Config struct {
	Server   ServerConfig
	Database DatabaseConfig
}

type ServerConfig struct {
	Port        string
	ContextPath string
	LogLevel    string
}

// DatabaseConfig holds database related configuration
type DatabaseConfig struct {
	Host     string
	Port     int
	User     string
	Password string
	DBName   string
	SSLMode  string

	// Connection pool configuration. Sensible defaults are applied below so
	// the service is safe out of the box; deployments can override via
	// DB_MAX_OPEN_CONNS, DB_MAX_IDLE_CONNS, DB_CONN_MAX_LIFETIME.
	// A zero env value falls back to whichever Go-literal default the loader
	// passes in (e.g. 25, 5, 5*time.Minute).
	DBMaxOpenConns    int
	DBMaxIdleConns    int
	DBConnMaxLifetime time.Duration
}

// Load loads the configuration from environment variables
func Load() (*Config, error) {
	if err := godotenv.Load(); err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Warn().Err(err).Msg(".env file found but failed to load")
	}

	return &Config{
		Server: ServerConfig{
			Port:        getEnv("SERVER_PORT", "8080"),
			ContextPath: getEnv("SERVER_CONTEXT_PATH", "/access"),
			LogLevel:    getEnv("LOG_LEVEL", "info"),
		},
		Database: DatabaseConfig{
			Host:              getEnv("DB_HOST", "localhost"),
			Port:              getEnvInt("DB_PORT", 5432),
			User:              getEnv("DB_USER", "postgres"),
			Password:          getEnv("DB_PASSWORD", "1234"),
			DBName:            getEnv("DB_NAME", "accesscontrol"),
			SSLMode:           getEnv("DB_SSL_MODE", "disable"),
			DBMaxOpenConns:    getEnvInt("DB_MAX_OPEN_CONNS", 25),
			DBMaxIdleConns:    getEnvInt("DB_MAX_IDLE_CONNS", 5),
			DBConnMaxLifetime: getEnvDuration("DB_CONN_MAX_LIFETIME", 5*time.Minute),
		},
	}, nil
}

func getEnv(key, def string) string {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	return v
}

func getEnvInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	i, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return i
}

// getEnvDuration parses a Go duration string (e.g. "5m", "30s", "1h30m").
// Returns def if the env var is unset or unparseable.
func getEnvDuration(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return def
	}
	return d
}
