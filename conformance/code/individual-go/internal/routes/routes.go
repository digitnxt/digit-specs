package routes

import (
	"individual/internal/config"
	"individual/internal/handlers"
	"individual/internal/middleware"

	tenantmigration "github.com/digitnxt/digit3/src/libraries/tenant-migration"
	"github.com/digitnxt/digit3/src/libraries/tenant-migration/tenantdb"
	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

func SetupRouter(cfg *config.Config, individualHandler *handlers.IndividualHandler, configHandler *handlers.ConfigHandler, dbConn interface{}, migrationSvc *tenantmigration.Service) *gin.Engine {
	// gin.New() (not gin.Default()) — we skip gin.Logger() so the JSON log
	// stream stays clean. Application-level logs cover request observability.
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(middleware.RequestID())

	if cfg.OpenTelemetry.Enabled {
		router.Use(tracerobs.TracingMiddleware(cfg.OpenTelemetry.ServiceName))
	}
	if cfg.OpenTelemetry.MetricsEnabled {
		router.Use(tracerobs.MetricsMiddleware())
	}

	gormDB := dbConn.(*gorm.DB)

	// Health check (no auth required, no tenant context).
	router.GET("/health", individualHandler.HealthCheck)

	// Internal migration handler for testing - allows direct migration trigger without account creation.
	if migrationSvc != nil {
		router.POST("/internal/migrate", gin.WrapF(migrationSvc.HandleMigrate))
	}

	// REST API v3 routes.
	//
	// Middleware order on the API group (matters):
	//   1. ExtractHeaders — validates X-Tenant-ID / X-User-ID and emits our
	//      []Error envelope on missing headers.
	//   2. tenantdb.GinMiddleware — sets PostgreSQL search_path to the
	//      tenant schema and wraps the request in a transaction with
	//      auto-commit/rollback.
	//
	// Mounting tenantdb on the api group (instead of the router root)
	// keeps it off /health, /internal/*, and unknown URLs so we don't open
	// a DB transaction for requests that won't match any route.
	api := router.Group(cfg.Server.ContextPath)
	api.Use(middleware.ExtractHeaders())
	api.Use(tenantdb.GinMiddleware(gormDB, "X-Tenant-ID"))
	{
		// Individuals resource
		individuals := api.Group("/v3/individuals")
		{
			individuals.POST("", individualHandler.CreateIndividualREST)
			individuals.GET("", individualHandler.SearchIndividualsQuery)
			individuals.GET("/exists", individualHandler.CheckIndividualExists)
			individuals.GET("/:id", individualHandler.GetIndividual)
			individuals.PUT("/:id", individualHandler.UpdateIndividualPut)
			individuals.DELETE("/:id", individualHandler.DeleteIndividualByID)
		}

		// Configs
		configs := api.Group("/v3/configs")
		{
			configs.POST("", configHandler.Upsert)
			configs.GET("", configHandler.Get)
		}
	}

	return router
}
