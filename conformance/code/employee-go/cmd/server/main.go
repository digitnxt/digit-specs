package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	tenantmigration "github.com/digitnxt/digit3/src/libraries/tenant-migration"

	"github.com/sirupsen/logrus"
	"gorm.io/gorm"

	"employee/db"
	"employee/internal/clients/boundary"
	"employee/internal/clients/idgen"
	"employee/internal/clients/individual"
	"employee/internal/clients/keycloak"
	"employee/internal/config"
	"employee/internal/handler"
	"employee/internal/pubsub"
	"employee/internal/repository"
	"employee/internal/router"
	employeeService "employee/internal/service"
	"employee/pkg/observability"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
)

func main() {
	// Load configuration
	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("Failed to load configuration: %v", err)
	}

	// Initialize structured logging with trace correlation
	tracerobs.InitializeOTelLogger(cfg.OpenTelemetry.ServiceName, cfg.Logging.ConsoleLogsEnabled)
	otelLogger := tracerobs.GetOTelLogger()

	// Initialize OpenTelemetry (traces, metrics, logs)
	telemetryConfig := tracerobs.TelemetryConfig{
		ServiceName:        cfg.OpenTelemetry.ServiceName,
		ServiceVersion:     cfg.OpenTelemetry.ServiceVersion,
		OTLPEndpoint:       cfg.OpenTelemetry.OTLPEndpoint,
		SamplingRatio:      cfg.OpenTelemetry.SamplingRatio,
		Enabled:            cfg.OpenTelemetry.Enabled,
		MetricsEnabled:     cfg.OpenTelemetry.MetricsEnabled,
		PrometheusPort:     cfg.OpenTelemetry.PrometheusPort,
		ConsoleLogsEnabled: cfg.Logging.ConsoleLogsEnabled,
	}
	shutdown, err := tracerobs.SetupTelemetry(telemetryConfig)
	if err != nil {
		log.Fatalf("Failed to setup telemetry: %v", err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdown(shutdownCtx); err != nil {
			otelLogger.ErrorWithTrace(shutdownCtx, err, "Error shutting down telemetry")
		}
	}()

	// Initialize business-specific metrics
	if err := observability.InitializeBusinessMetrics(); err != nil {
		log.Fatalf("Failed to initialize business metrics: %v", err)
	}

	otelLogger.InfoWithTrace(context.Background(), "Starting employee service", map[string]interface{}{
		"service": cfg.OpenTelemetry.ServiceName,
		"version": cfg.OpenTelemetry.ServiceVersion,
	})

	// Initialize database connection
	dbConn, err := initDatabase(cfg)
	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}

	// Register GORM tracing plugin to automatically capture SQL queries
	if err := tracerobs.RegisterGormTracing(dbConn); err != nil {
		log.Fatalf("Failed to register GORM tracing: %v", err)
	}

	otelLogger.InfoWithTrace(context.Background(), "Database connection established", nil)

	// Initialize PubSub client if enabled
	pubsubClient := pubsub.NewPubSubClient(cfg, otelLogger)

	// Connect PubSub client if initialized
	if pubsubClient != nil {
		if err := pubsubClient.Connect(); err != nil {
			otelLogger.ErrorWithTrace(context.Background(), err, "Failed to connect PubSub client - service will continue without pub/sub", map[string]interface{}{
				"pubsub_type": cfg.PubSub.Type,
			})
			pubsubClient = nil // Disable PubSub on connection failure
		} else {
			otelLogger.InfoWithTrace(context.Background(), "PubSub client connected successfully", map[string]interface{}{
				"pubsub_type": cfg.PubSub.Type,
			})
		}

		// Cleanup PubSub client on shutdown (only if successfully connected)
		if pubsubClient != nil {
			defer func() {
				if err := pubsubClient.Disconnect(); err != nil {
					otelLogger.ErrorWithTrace(context.Background(), err, "Error disconnecting PubSub client")
				}
			}()
		}
	}

	// Create event publisher
	eventPublisher := pubsub.NewEventPublisher(pubsubClient, cfg)

	// ============================================
	// TENANT MIGRATION SETUP
	// ============================================
	log.Printf("Initializing tenant migration service...")
	migrationCfg := tenantmigration.ConfigFromEnv()

	// Override with service-specific database credentials and paths
	dsn := db.BuildPostgresDSN(cfg)
	migrationCfg.DBURL = dsn
	migrationCfg.FlywayUser = cfg.Database.DBUser
	migrationCfg.FlywayPassword = cfg.Database.DBPassword
	migrationCfg.FlywayLocations = cfg.TenantMigration.FlywayLocations
	migrationCfg.MigrateScriptPath = cfg.TenantMigration.MigrateScriptPath
	migrationCfg.FlywayBinPath = cfg.TenantMigration.FlywayBinPath
	migrationCfg.SchemaTable = cfg.TenantMigration.SchemaTable

	log.Printf("Migration config loaded:")
	log.Printf("  Enabled: %v", migrationCfg.Enabled)
	log.Printf("  Schema separation mode: %v", migrationCfg.SchemaSeparationMode)
	log.Printf("  Flyway locations: %s", migrationCfg.FlywayLocations)
	log.Printf("  Migration script path: %s", migrationCfg.MigrateScriptPath)
	log.Printf("  Flyway bin path: %s", migrationCfg.FlywayBinPath)
	log.Printf("  Schema table: %s", migrationCfg.SchemaTable)

	var migrationSvc *tenantmigration.Service
	migrationSvc, err = tenantmigration.NewService(migrationCfg, log.Default())
	if err != nil {
		log.Printf("migration service disabled: %v", err)
		migrationSvc = nil
	} else {
		log.Printf("✓ Tenant migration service initialized")

		// Start migration consumer in background
		migrationConsumer := pubsub.NewMigrationConsumer(pubsubClient, cfg, migrationSvc)
		go migrationConsumer.StartWithRetry(context.Background())
		log.Printf("✓ Migration consumer started")
	}

	// Initialize repositories
	employeeRepo := repository.NewEmployeeRepository(dbConn)
	jurisdictionRepo := repository.NewJurisdictionRepository(dbConn)

	// Initialize ID generation client
	idGenClient := idgen.NewClient(idgen.Config{
		Host:      cfg.IDGen.Host,
		Path:      cfg.IDGen.Path,
		IDGenName: cfg.IDGen.IDGenName,
		Enabled:   cfg.IDGen.Enabled,
	})

	boundaryClient := boundary.NewClient(cfg.Boundary)
	individualClient := individual.NewClient(cfg.Individual)
	keycloakClient := keycloak.NewClient(cfg.Keycloak)

	// First, create employee service with a nil jurisdiction service
	employeeSvc := employeeService.NewEmployeeService(employeeRepo, nil, idGenClient, individualClient, keycloakClient, cfg, eventPublisher)

	// Then create jurisdiction service with the employee service
	jurisdictionSvc := employeeService.NewJurisdictionService(
		jurisdictionRepo,
		employeeSvc,
		boundaryClient,
		cfg,
		eventPublisher,
	)
	// Now update the employee service with the jurisdiction service
	employeeSvc = employeeService.NewEmployeeService(employeeRepo, jurisdictionSvc, idGenClient, individualClient, keycloakClient, cfg, eventPublisher)

	// Initialize a logrus logger for handlers (they retain the logrus interface)
	handlerLogger := initLogger()

	// Initialize handlers
	employeeHandler := handler.NewEmployeeHandler(employeeSvc, handlerLogger, cfg.Keycloak.Enabled)
	jurisdictionHandler := handler.NewJurisdictionHandler(jurisdictionSvc, handlerLogger)

	// Setup router
	r := router.SetupRouter(cfg, employeeHandler, jurisdictionHandler, handlerLogger, dbConn, migrationSvc)

	// Start server in a goroutine
	server := &http.Server{
		Addr:         ":" + cfg.Server.Port,
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	go func() {
		otelLogger.InfoWithTrace(context.Background(), "Server started", map[string]interface{}{
			"addr": server.Addr,
		})
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	// Wait for interrupt signal to gracefully shut down the server
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	otelLogger.InfoWithTrace(context.Background(), "Shutting down server...", nil)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatalf("Server forced to shutdown: %v", err)
	}

	// Close database connection
	sqlDB, err := dbConn.DB()
	if err == nil {
		sqlDB.Close()
	}

	otelLogger.InfoWithTrace(context.Background(), "Server exiting", nil)
}

// initLogger initializes and configures the logger
func initLogger() *logrus.Logger {
	logger := logrus.New()
	logger.SetFormatter(&logrus.JSONFormatter{
		TimestampFormat: time.RFC3339,
	})

	// Set log level based on environment
	logLevel := os.Getenv("LOG_LEVEL")
	if logLevel == "" {
		logLevel = "info"
	}

	level, err := logrus.ParseLevel(logLevel)
	if err != nil {
		logger.Warnf("Invalid log level '%s', defaulting to 'info'", logLevel)
		level = logrus.InfoLevel
	}
	logger.SetLevel(level)

	return logger
}

// initDatabase initializes the database connection
func initDatabase(cfg *config.Config) (*gorm.DB, error) {
	dbConn, err := db.InitDB(cfg)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}
	if os.Getenv("GIN_MODE") != "release" {
		dbConn = dbConn.Debug()
	}
	return dbConn, nil
}
