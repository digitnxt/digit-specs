package middleware

import (
	"net/http"

	"individual/internal/common"
	"individual/internal/models"

	"github.com/gin-gonic/gin"
)

const (
	HeaderTenantID           = "X-Tenant-ID"
	HeaderUserID             = "X-User-ID"
	ContextKeyRequestContext = "requestContext"
)

// ExtractHeaders extracts and validates required headers. Missing-header
// responses use the same []Error envelope as the rest of the API so clients
// can decode every error with one shape — see bug.md #9.
func ExtractHeaders() gin.HandlerFunc {
	return func(c *gin.Context) {
		tenantID := c.GetHeader(HeaderTenantID)
		userID := c.GetHeader(HeaderUserID)

		// Validate required headers
		if tenantID == "" {
			c.AbortWithStatusJSON(http.StatusBadRequest, []models.Error{{
				Code:    common.ErrorMissingHeader,
				Message: "Missing mandatory header: X-Tenant-ID",
			}})
			return
		}

		// X-User-ID is required only for mutating operations (it stamps
		// createdBy/modifiedBy). Read-only requests (GET) don't need it.
		if userID == "" && c.Request.Method != http.MethodGet {
			c.AbortWithStatusJSON(http.StatusBadRequest, []models.Error{{
				Code:    common.ErrorMissingHeader,
				Message: "Missing required header: X-User-ID",
			}})
			return
		}

		// Store in context
		requestIDVal, _ := c.Get(RequestIDKey)
		requestID, _ := requestIDVal.(string)
		reqContext := &models.RequestContext{
			TenantID:  tenantID,
			UserID:    userID,
			RequestID: requestID,
		}

		c.Set(ContextKeyRequestContext, reqContext)
		c.Next()
	}
}

// GetRequestContext retrieves RequestContext from Gin context
func GetRequestContext(c *gin.Context) *models.RequestContext {
	value, exists := c.Get(ContextKeyRequestContext)
	if !exists {
		return &models.RequestContext{}
	}

	reqContext, ok := value.(*models.RequestContext)
	if !ok {
		return &models.RequestContext{}
	}

	return reqContext
}
