package routes

import (
	"net/http"

	"accesscontrol/internal/config"
	"accesscontrol/internal/handler"
	"accesscontrol/internal/model"

	"github.com/gin-gonic/gin"
	"github.com/rs/zerolog/log"
)

func requireTenantID(c *gin.Context) {
	if c.GetHeader("X-Tenant-ID") == "" {
		log.Warn().Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("missing X-Tenant-ID header")
		c.AbortWithStatusJSON(http.StatusBadRequest, model.Errors("AccessControl.MissingTenantId", "X-Tenant-ID header is required"))
		return
	}
	c.Next()
}

func requireUserID(c *gin.Context) {
	if c.GetHeader("X-User-ID") == "" {
		log.Warn().Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("missing X-User-ID header")
		c.AbortWithStatusJSON(http.StatusBadRequest, model.Errors("AccessControl.MissingUserId", "X-User-ID header is required"))
		return
	}
	c.Next()
}

// NewRouter creates a new gin router.
//
// We use gin.New() (not gin.Default()) so we can opt out of gin.Logger().
// gin.Logger() emits a plain-text request line per call ("[GIN] ..."),
// which pollutes the otherwise-JSON zerolog stream and bypasses our
// structured log format. Request-level observability comes from
// application-level handler logs (and OTel spans when enabled), not from
// a separate gin log line.
//
// gin.Recovery() is kept explicitly — we still want a panic to become a
// 500 response rather than crashing the process.
func NewRouter(handlers *handler.Handlers, cfg *config.Config) *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"status":  "healthy",
			"service": "accesscontrol",
		})
	})

	api := r.Group(cfg.Server.ContextPath)
	{
		// RBAC Routes — all require X-Tenant-ID; writes also require X-User-ID
		rbacRules := api.Group("/v3/rbac/rules", requireTenantID)
		{
			rbacRules.GET("/", handlers.ListRbacRules)
			rbacRules.POST("/", requireUserID, handlers.CreateRbacRule)
			rbacRules.POST("/bulk", requireUserID, handlers.BulkCreateRbacRules)
			rbacRules.DELETE("/tenant", handlers.DeleteRbacRulesByTenant)

			rbacRuleID := rbacRules.Group("/:id")
			{
				rbacRuleID.GET("/", handlers.GetRbacRule)
				rbacRuleID.PATCH("/", requireUserID, handlers.UpdateRbacRule)
				rbacRuleID.DELETE("/", handlers.DeleteRbacRule)
			}
		}

		// JBAC Routes — all require X-Tenant-ID; writes also require X-User-ID
		jbacRules := api.Group("/v3/jbac/rules", requireTenantID)
		{
			jbacRules.GET("/", handlers.ListJbacRules)
			jbacRules.POST("/", requireUserID, handlers.CreateJbacRule)
			jbacRules.POST("/bulk", requireUserID, handlers.BulkCreateJbacRules)
			jbacRules.DELETE("/tenant", handlers.DeleteJbacRulesByTenant)

			jbacRuleID := jbacRules.Group("/:id")
			{
				jbacRuleID.GET("/", handlers.GetJbacRule)
				jbacRuleID.PATCH("/", requireUserID, handlers.UpdateJbacRule)
				jbacRuleID.DELETE("/", handlers.DeleteJbacRule)
			}
		}

		// Internal routes — no header requirements
		internal := api.Group("/v3/internal")
		{
			internal.GET("/rbac/rules", handlers.ListAllRbacRules)
			internal.GET("/rbac/rules/version", handlers.GetAllRbacRulesVersion)
			internal.GET("/jbac/rules", handlers.ListAllJbacRules)
			internal.GET("/jbac/rules/version", handlers.GetAllJbacRulesVersion)
		}
	}

	return r
}
