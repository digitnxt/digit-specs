package middleware

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"employee/internal/models"
	"employee/pkg/errors"
)

const (
	TenantIDHeader = "X-Tenant-ID"
	UserIDHeader   = "X-User-ID"
)

// contextKey is an unexported type so our key can't collide with any other
// package's ctx key in the request graph.
type contextKey string

const requestContextKey contextKey = "requestContext"

// RequestContext carries request-scoped identity propagated through ctx.
type RequestContext struct {
	TenantID string
	UserID   string
}

// Headers validates that X-Tenant-ID is present on every non-health request,
// and X-User-ID on every mutating (non-GET) request, and exposes them to:
//   - handlers via c.Get("tenantID") / c.Get("userID")
//   - service-layer code via GetRequestContextFromContext(ctx)
func Headers(logger *logrus.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.URL.Path == "/health" {
			c.Next()
			return
		}

		tenantID := c.GetHeader(TenantIDHeader)
		if tenantID == "" {
			abortMissingHeader(c, logger, TenantIDHeader)
			return
		}
		// X-User-ID is required only for mutating operations (it stamps
		// createdBy/modifiedBy). Read-only requests (GET) don't need it.
		userID := c.GetHeader(UserIDHeader)
		if userID == "" && c.Request.Method != http.MethodGet {
			abortMissingHeader(c, logger, UserIDHeader)
			return
		}

		c.Set("tenantID", tenantID)
		c.Set("userID", userID)

		ctx := context.WithValue(c.Request.Context(), requestContextKey, &RequestContext{
			TenantID: tenantID,
			UserID:   userID,
		})
		c.Request = c.Request.WithContext(ctx)

		c.Next()
	}
}

func abortMissingHeader(c *gin.Context, logger *logrus.Logger, header string) {
	logger.WithField("header", header).Error("Missing required header")
	c.AbortWithStatusJSON(http.StatusBadRequest, []models.Error{{
		Code:    errors.CodeMissingHeader,
		Message: header + " header is required",
	}})
}

// GetRequestContextFromContext recovers RequestContext from a stdlib
// context.Context. Returns a zero-value RequestContext when absent so
// callers don't need a nil check.
func GetRequestContextFromContext(ctx context.Context) *RequestContext {
	if rc, ok := ctx.Value(requestContextKey).(*RequestContext); ok {
		return rc
	}
	return &RequestContext{}
}
