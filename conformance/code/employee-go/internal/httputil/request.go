package httputil

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/sirupsen/logrus"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"employee/pkg/errors"
)

// ResolveTenantID extracts the tenantID set by middleware and type-asserts it
// to string. On failure (absent or wrong type), writes a 500 response and
// returns ok=false; callers should return immediately. On success, sets the
// `tenant.id` attribute on the active span.
func ResolveTenantID(c *gin.Context, span trace.Span, logger *logrus.Logger) (string, bool) {
	raw, exists := c.Get("tenantID")
	if !exists {
		e := errors.New(errors.CodeInternal, "Tenant ID not found in context")
		span.RecordError(e)
		span.SetStatus(codes.Error, "Missing tenant ID")
		WriteError(c, logger, http.StatusInternalServerError, e)
		return "", false
	}
	id, ok := raw.(string)
	if !ok {
		e := errors.New(errors.CodeInternal, "Invalid tenant ID format")
		span.RecordError(e)
		span.SetStatus(codes.Error, "Invalid tenant ID")
		WriteError(c, logger, http.StatusInternalServerError, e)
		return "", false
	}
	span.SetAttributes(attribute.String("tenant.id", id))
	return id, true
}

// RequireUUIDParam reads a path param by name, validates it as a UUID, and
// writes 400 + returns ok=false on failure. `label` is the human-readable
// resource name used in the error message ("employee", "jurisdiction").
func RequireUUIDParam(c *gin.Context, span trace.Span, logger *logrus.Logger, paramName, label string) (string, bool) {
	id := c.Param(paramName)
	if _, err := uuid.Parse(id); err != nil {
		e := errors.New(errors.CodeInvalidUUID, "Invalid "+label+" UUID")
		span.RecordError(e)
		span.SetStatus(codes.Error, "Invalid UUID")
		WriteError(c, logger, http.StatusBadRequest, e)
		return "", false
	}
	return id, true
}

// RequireAuthHeader pulls the Authorization header, writing 401 + returning
// ok=false when missing.
func RequireAuthHeader(c *gin.Context, span trace.Span, logger *logrus.Logger) (string, bool) {
	auth := c.GetHeader("Authorization")
	if auth == "" {
		e := errors.New(errors.CodeUnauthorized, "Authorization header is missing")
		span.RecordError(e)
		span.SetStatus(codes.Error, "Missing authorization")
		WriteError(c, logger, http.StatusUnauthorized, e)
		return "", false
	}
	return auth, true
}

// FailService is the convenience tail for the "service returned an error"
// path every handler shares: records the span error, writes a 500 response
// (which WriteError upgrades to a more specific status via StatusForCode for
// typed *errors.Error values). Callers should `return` immediately after.
func FailService(c *gin.Context, span trace.Span, logger *logrus.Logger, err error, spanMsg string) {
	span.RecordError(err)
	span.SetStatus(codes.Error, spanMsg)
	WriteError(c, logger, http.StatusInternalServerError, err)
}
