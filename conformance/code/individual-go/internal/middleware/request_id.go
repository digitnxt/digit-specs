package middleware

import (
	"individual/internal/common"

	"github.com/gin-gonic/gin"
	"github.com/rs/zerolog/log"
)

const (
	RequestIDHeader = "X-Request-Id"
	RequestIDKey    = "requestID"
)

// RequestID assigns / propagates the request ID, echoes it back to clients per
// spec, and attaches it as `requestID` to every zerolog line emitted while the
// handler chain runs — so a curl `?id=…` in the spec response header can be
// grep'd against the JSON log stream for the same request.
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader(RequestIDHeader)
		if requestID == "" {
			requestID = common.GenerateUUID()
		}

		c.Set(RequestIDKey, requestID)
		c.Header(RequestIDHeader, requestID)

		// Bind requestID into a context-scoped logger so downstream
		// `log.X().Ctx(c.Request.Context()).Msg(...)` calls carry it.
		ctxLogger := log.With().Str("requestID", requestID).Logger()
		c.Request = c.Request.WithContext(ctxLogger.WithContext(c.Request.Context()))

		c.Next()
	}
}
