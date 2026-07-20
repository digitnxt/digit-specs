package router

import (
	"net/http"

	tenantmigration "github.com/digitnxt/digit3/src/libraries/tenant-migration"
	tenantdb "github.com/digitnxt/digit3/src/libraries/tenant-migration/tenantdb"
	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"
	"gorm.io/gorm"

	"employee/internal/config"
	"employee/internal/handler"
	"employee/internal/middleware"
)

// SetupRouter initializes and configures the HTTP router
func SetupRouter(
	cfg *config.Config,
	employeeHandler *handler.EmployeeHandler,
	jurisdictionHandler *handler.JurisdictionHandler,
	logger *logrus.Logger,
	db *gorm.DB,
	migrationSvc *tenantmigration.Service,
) *gin.Engine {
	// Create router with default middleware
	r := gin.New()

	// Core middleware
	r.Use(gin.Recovery())
	r.Use(middleware.Logger())
	r.Use(middleware.Headers(logger))

	// Observability middleware (tracing + metrics with exemplars)
	if cfg.OpenTelemetry.Enabled {
		r.Use(tracerobs.TracingMiddleware(cfg.OpenTelemetry.ServiceName))
	}
	if cfg.OpenTelemetry.MetricsEnabled {
		r.Use(tracerobs.MetricsMiddleware())
	}

	// ============================================
	// APPLY TENANT MIDDLEWARE
	// ============================================
	// This middleware:
	// 1. Extracts X-Tenant-ID from header
	// 2. Sets PostgreSQL search_path to tenant schema
	// 3. Wraps request in transaction
	// 4. Auto-commits/rollbacks
	r.Use(tenantdb.GinMiddleware(db, "X-Tenant-ID"))

	// Health check endpoint
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status": "UP",
		})
	})

	// Internal migration handler for testing - allows direct migration trigger without account creation
	if migrationSvc != nil {
		r.POST("/internal/migrate", gin.WrapF(migrationSvc.HandleMigrate))
	}

	// API v3 routes
	v3 := r.Group(cfg.Server.ContextPath + "/v3")
	{
		employeeRoute := v3.Group("/employees")
		// Employee endpoints
		employeeRoute.POST("", employeeHandler.CreateEmployees)
		employeeRoute.GET("", employeeHandler.SearchEmployees)

		// Employee by ID endpoints
		employeeID := employeeRoute.Group("/:id")
		{
			employeeID.GET("", employeeHandler.GetEmployeeByUUID)
			employeeID.PUT("", employeeHandler.UpdateEmployee)
			employeeID.DELETE("", employeeHandler.HardDeleteEmployee)
			employeeID.PATCH("", employeeHandler.PatchEmployee)

			// Employee status management
			employeeID.POST("deactivate", employeeHandler.DeactivateEmployee)
			employeeID.POST("reactivate", employeeHandler.ReactivateEmployee)

			// Jurisdictions nested under the owning employee
			jurisdictions := employeeID.Group("/jurisdictions")
			{
				jurisdictions.POST("", jurisdictionHandler.CreateJurisdiction)
				jurisdictions.GET("", jurisdictionHandler.SearchJurisdictions)
				jurisdictions.GET("/:jurisdictionId", jurisdictionHandler.GetJurisdictionByUUID)
				jurisdictions.PUT("/:jurisdictionId", jurisdictionHandler.UpdateJurisdiction)
			}
		}
	}

	return r
}
