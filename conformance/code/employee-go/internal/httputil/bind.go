package httputil

import (
	"bytes"
	"io"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"employee/pkg/errors"
)

// maxBindBodyLog caps how many bytes of the raw body are logged on a bind
// failure. Large payloads are truncated so a single bad request can't bloat
// the log line, but enough is kept to identify the malformed field.
const (
	// maxBindBodyLog caps how many bytes of the body are echoed into the log
	// line on a bind failure — a logging concern only.
	maxBindBodyLog = 8 * 1024
	// maxRequestBody is the hard ceiling on a bindable JSON body. It bounds
	// memory per request while sitting well above any legitimate payload (a
	// full 100-employee batch with jurisdictions is well under 1 MiB).
	maxRequestBody = 1 << 20 // 1 MiB
)

// BindJSON wraps c.ShouldBindJSON. On failure it logs the raw request body
// alongside the bind error and request identity (path, X-User-ID, X-Tenant-ID)
// so payload-shape problems from upstream callers — e.g. {"isActive":"false"}
// against a bool field — are debuggable from logs alone. The body is only
// logged on failure; well-formed requests stay out of logs.
func BindJSON(c *gin.Context, logger *logrus.Logger, dst interface{}) error {
	var raw []byte
	if c.Request.Body != nil {
		// Read the FULL body (bounded by maxRequestBody) so binding sees the
		// complete payload. Reading only maxBindBodyLog here and writing that
		// truncated copy back as the request body previously caused any body
		// larger than 8 KB (e.g. a batch create) to fail with "unexpected EOF".
		// The log line is truncated separately below.
		b, err := io.ReadAll(io.LimitReader(c.Request.Body, maxRequestBody+1))
		if err == nil {
			raw = b
			c.Request.Body = io.NopCloser(bytes.NewBuffer(b))
		}
	}

	// Reject an over-ceiling body before attempting to parse it, so a
	// deliberately huge payload can't drive the JSON decoder.
	if len(raw) > maxRequestBody {
		logger.WithFields(logrus.Fields{
			"method": c.Request.Method,
			"path":   c.Request.URL.Path,
			"bytes":  len(raw),
		}).Warn("Request body exceeds maximum size")
		return errors.New(errors.CodeInvalidRequest, "request body too large")
	}

	if err := c.ShouldBindJSON(dst); err != nil {
		truncated := len(raw) > maxBindBodyLog
		body := raw
		if truncated {
			body = body[:maxBindBodyLog]
		}
		logger.WithFields(logrus.Fields{
			"method":    c.Request.Method,
			"path":      c.Request.URL.Path,
			"tenantId":  c.GetHeader("X-Tenant-ID"),
			"userId":    c.GetHeader("X-User-ID"),
			"body":      string(body),
			"truncated": truncated,
			"error":     err.Error(),
		}).Warn("Bind failed for request")
		return errors.New(errors.CodeInvalidRequest, err.Error())
	}
	return nil
}

// BindBody is the handler-facing convenience wrapper over BindJSON: on
// failure it records the error on the active span, writes a 400 response
// via WriteError, and returns ok=false. Callers should return immediately
// when ok=false — no further response should be written.
func BindBody(c *gin.Context, span trace.Span, logger *logrus.Logger, dst interface{}) bool {
	if err := BindJSON(c, logger, dst); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "Invalid request payload")
		WriteError(c, logger, http.StatusBadRequest, err)
		return false
	}
	return true
}
