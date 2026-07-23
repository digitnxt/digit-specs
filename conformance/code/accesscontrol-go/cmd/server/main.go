package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"accesscontrol/db"
	"accesscontrol/internal/config"
	"accesscontrol/internal/handler"
	"accesscontrol/internal/repository"
	"accesscontrol/internal/routes"
	"accesscontrol/internal/service"

	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
)

func main() {
	// Use zerolog's default time format (RFC3339 with millisecond precision)
	// to match the rest of the platform — billing, account, localization, etc.
	// all rely on the same default. Overriding to TimeFormatUnix here would
	// emit integer epoch seconds, which is hard to read in `kubectl logs` tail
	// and inconsistent with peer services.

	// Load config
	cfg, err := config.Load()
	if err != nil {
		log.Fatal().Err(err).Msg("failed to load config")
	}

	level, err := zerolog.ParseLevel(cfg.Server.LogLevel)
	if err != nil {
		log.Warn().Str("LOG_LEVEL", cfg.Server.LogLevel).Msg("invalid log level, defaulting to info")
		level = zerolog.InfoLevel
	}
	zerolog.SetGlobalLevel(level)
	log.Info().Str("logLevel", level.String()).Msg("logger initialized")

	// Create DB pool
	db, err := db.NewDBPool(cfg)
	if err != nil {
		log.Fatal().Err(err).Msg("failed to create db pool")
	}

	// Create repository, service, and handlers
	repo := repository.NewGormRepository(db)
	svc := service.NewRBACService(repo)
	handlers := handler.NewHandlers(svc)

	// Create router
	router := routes.NewRouter(handlers, cfg)

	// Create server
	server := &http.Server{
		Addr:    fmt.Sprintf(":%s", cfg.Server.Port),
		Handler: router,
	}

	// Graceful shutdown
	go func() {
		quit := make(chan os.Signal, 1)
		signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
		<-quit
		log.Info().Msg("shutting down server...")

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		if err := server.Shutdown(ctx); err != nil {
			log.Fatal().Err(err).Msg("server shutdown failed")
		}
	}()

	log.Info().Msgf("starting server on port %s", cfg.Server.Port)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal().Err(err).Msg("server failed to start")
	}

	log.Info().Msg("server stopped")
}
