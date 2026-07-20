package main

import (
	"context"
	stdlog "log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"individual/db"
	"individual/internal/clients"
	config "individual/internal/config"
	"individual/internal/handlers"
	"individual/internal/pubsub"
	"individual/internal/repository"
	"individual/internal/routes"
	"individual/internal/service"
	"individual/internal/validator"
	"individual/pkg/observability"

	tenantmigration "github.com/digitnxt/digit3/src/libraries/tenant-migration"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	"github.com/gin-gonic/gin"
	"github.com/gin-gonic/gin/binding"
	goval "github.com/go-playground/validator/v10"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"reflect"
	"strings"
)

// init wires the gin validator engine so fe.Field() returns the wire-level
// name (form / json tag) rather than the Go struct field name. Without this
// hook, search-side validation errors would surface paths like
// "IndividualSearchFilter.Size" / "Gender" instead of "size" / "gender".
// See bug.md #11.
func init() {
	v, ok := binding.Validator.Engine().(*goval.Validate)
	if !ok {
		return
	}
	v.RegisterTagNameFunc(func(fld reflect.StructField) string {
		if name := strings.SplitN(fld.Tag.Get("form"), ",", 2)[0]; name != "" && name != "-" {
			return name
		}
		if name := strings.SplitN(fld.Tag.Get("json"), ",", 2)[0]; name != "" && name != "-" {
			return name
		}
		return fld.Name
	})
}

func main() {
	// Load configuration first so we can honor LOG_LEVEL before emitting anything.
	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatal().Err(err).Msg("failed to load configuration")
	}

	// Apply zerolog level from config.
	level, err := zerolog.ParseLevel(cfg.Logging.Level)
	if err != nil {
		log.Warn().Err(err).Str("LOG_LEVEL", cfg.Logging.Level).Msg("invalid log level, defaulting to info")
		level = zerolog.InfoLevel
	}
	zerolog.SetGlobalLevel(level)

	log.Info().Str("logLevel", level.String()).Msg("starting individual service")

	// Initialize structured logging with trace correlation (from tracer package)
	tracerobs.InitializeOTelLogger(cfg.OpenTelemetry.ServiceName, cfg.Logging.ConsoleLogsEnabled)
	logger := tracerobs.GetOTelLogger()

	// Initialize OpenTelemetry (traces, metrics, logs) from tracer package
	telemetryCfg := tracerobs.TelemetryConfig{
		ServiceName:        cfg.OpenTelemetry.ServiceName,
		ServiceVersion:     cfg.OpenTelemetry.ServiceVersion,
		OTLPEndpoint:       cfg.OpenTelemetry.OTLPEndpoint,
		SamplingRatio:      cfg.OpenTelemetry.SamplingRatio,
		Enabled:            cfg.OpenTelemetry.Enabled,
		MetricsEnabled:     cfg.OpenTelemetry.MetricsEnabled,
		PrometheusPort:     cfg.OpenTelemetry.PrometheusPort,
		ConsoleLogsEnabled: cfg.Logging.ConsoleLogsEnabled,
	}
	shutdown, err := tracerobs.SetupTelemetry(telemetryCfg)
	if err != nil {
		log.Fatal().Err(err).Msg("failed to setup telemetry")
	}
	defer func() {
		shutCtx, shutCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer shutCancel()
		if err := shutdown(shutCtx); err != nil {
			logger.ErrorWithTrace(shutCtx, err, "Error shutting down telemetry")
		}
	}()

	// Initialize business-specific metrics
	if err := observability.InitializeBusinessMetrics(); err != nil {
		log.Fatal().Err(err).Msg("failed to initialize business metrics")
	}

	gin.SetMode(cfg.Server.GinMode)

	dbConn, err := db.NewDatabase(cfg)
	if err != nil {
		log.Fatal().Err(err).Msg("failed to connect to database")
	}
	log.Info().Msg("database connected")

	// Register GORM tracing plugin to automatically capture SQL queries in spans
	if err := tracerobs.RegisterGormTracing(dbConn); err != nil {
		log.Fatal().Err(err).Msg("failed to register GORM tracing")
	}

	// External clients
	var vaultClient clients.VaultClient
	if cfg.Vault.Enabled {
		vaultClient, err = clients.NewVaultClient(&cfg.Vault)
		if err != nil {
			log.Fatal().Err(err).Msg("failed to initialize Vault client")
		}
		log.Info().Str("vaultAddr", cfg.Vault.Address).Msg("Vault client initialized")
	} else {
		log.Info().Msg("Vault encryption disabled")
	}

	idgenClient := clients.NewIDGenClient(&cfg.IDGen)

	log.Debug().Msg("external clients initialized")

	// Initialize PubSub client if enabled
	pubsubClient := pubsub.NewPubSubClient(cfg, logger)

	// Connect PubSub client if initialized
	if pubsubClient != nil {
		if err := pubsubClient.Connect(); err != nil {
			logger.ErrorWithTrace(context.Background(), err, "Failed to connect PubSub client - service will continue without pub/sub", map[string]interface{}{
				"pubsub_type": cfg.PubSub.Type,
			})
			pubsubClient = nil // Disable PubSub on connection failure
		} else {
			logger.InfoWithTrace(context.Background(), "PubSub client connected successfully", map[string]interface{}{
				"pubsub_type": cfg.PubSub.Type,
			})
		}

		// Cleanup PubSub client on shutdown (only if successfully connected)
		if pubsubClient != nil {
			defer func() {
				if err := pubsubClient.Disconnect(); err != nil {
					logger.ErrorWithTrace(context.Background(), err, "Error disconnecting PubSub client")
				}
			}()
		}
	}

	// Create event publisher
	eventPublisher := pubsub.NewEventPublisher(pubsubClient, cfg)

	// Tenant migration setup.
	log.Debug().Msg("initializing tenant migration service")
	migrationCfg := tenantmigration.ConfigFromEnv()

	dsn := db.BuildPostgresDSN(cfg)
	migrationCfg.DBURL = dsn
	migrationCfg.FlywayUser = cfg.Database.DBUser
	migrationCfg.FlywayPassword = cfg.Database.DBPassword
	migrationCfg.FlywayLocations = cfg.TenantMigration.FlywayLocations
	migrationCfg.MigrateScriptPath = cfg.TenantMigration.MigrateScriptPath
	migrationCfg.FlywayBinPath = cfg.TenantMigration.FlywayBinPath
	migrationCfg.SchemaTable = cfg.TenantMigration.SchemaTable

	log.Debug().
		Bool("enabled", migrationCfg.Enabled).
		Bool("schemaSeparationMode", migrationCfg.SchemaSeparationMode).
		Str("flywayLocations", migrationCfg.FlywayLocations).
		Str("migrateScriptPath", migrationCfg.MigrateScriptPath).
		Str("flywayBinPath", migrationCfg.FlywayBinPath).
		Str("schemaTable", migrationCfg.SchemaTable).
		Msg("migration config loaded")

	var migrationSvc *tenantmigration.Service
	migrationSvc, err = tenantmigration.NewService(migrationCfg, stdlog.Default())
	if err != nil {
		log.Warn().Err(err).Msg("migration service disabled")
		migrationSvc = nil
	} else {
		log.Info().Msg("tenant migration service initialized")

		// Start migration consumer in background
		migrationConsumer := pubsub.NewMigrationConsumer(pubsubClient, cfg, migrationSvc)
		go migrationConsumer.StartWithRetry(context.Background())
		log.Info().Msg("migration consumer started")
	}

	// Initialize repositories
	individualRepo := repository.NewIndividualRepository(dbConn)
	cfgRepo := repository.NewConfigRepository(dbConn)

	// Initialize services
	encryptionService := service.NewEncryptionService(vaultClient, &cfg.Vault, []byte(cfg.HMACSecret))
	enrichmentService := service.NewEnrichmentService(idgenClient, &cfg.IDGen)

	// Validator — owned by the handler layer, runs before each service call.
	requestValidator := validator.NewValidator(individualRepo, cfgRepo, []byte(cfg.HMACSecret))

	// Services — no longer hold a validator dependency; they trust the
	// handler to validate input before invoking them.
	individualService := service.NewIndividualService(
		individualRepo,
		enrichmentService,
		encryptionService,
		eventPublisher,
		cfg,
	)
	configService := service.NewConfigService(cfgRepo, eventPublisher, cfg)

	// Handlers — each receives the shared validator.
	individualHandler := handlers.NewIndividualHandler(individualService, requestValidator)
	configHandler := handlers.NewConfigHandler(configService, requestValidator)

	log.Debug().Msg("router setup completed")

	server := &http.Server{
		Addr:         ":" + cfg.Server.Port,
		Handler:      routes.SetupRouter(cfg, individualHandler, configHandler, dbConn, migrationSvc),
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	go func() {
		log.Info().Str("port", cfg.Server.Port).Msg("server listening")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal().Err(err).Msg("failed to start server")
		}
	}()

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info().Msg("shutting down server")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatal().Err(err).Msg("server forced to shutdown")
	}

	log.Info().Msg("server exited")
}
