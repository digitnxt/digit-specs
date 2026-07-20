package service

import (
	"context"

	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"employee/internal/pubsub"
	"employee/pkg/errors"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
)

// employeeServiceName is the OTel tracer name shared by every span emitted
// from the service package. The metric/log label is per-resource — see the
// per-file <resource>ServiceName constants.
const employeeServiceName = "employee-service"

// failOp encapsulates the "downstream call failed" tail every service method
// shares: record the error on the span, set the span error status with a
// human-readable message, emit the OTel error metric, structured-log with the
// caller-provided fields, and return a typed *errors.Error with the given
// domain code and message. The original err is preserved on the span and in
// the log; only the client-facing code+message are carried on the returned error.
func failOp(ctx context.Context, span trace.Span, logger *tracerobs.OTelLogger, serviceName, errCode, msg string, err error, fields map[string]interface{}) error {
	span.RecordError(err)
	span.SetStatus(codes.Error, msg)
	tracerobs.RecordError(ctx, errCode, serviceName)
	logger.ErrorWithTrace(ctx, err, msg, fields)
	return errors.New(errCode, msg)
}

// mapDownstreamErr is the specific tail for "load/mutate hit the repo and the
// repo may have returned ErrNotFound." It returns a typed not-found error
// (with Ok span status, since "not found" is a legitimate client response)
// when the underlying err matches ErrNotFound; otherwise it falls back to
// failOp with a DATABASE_ERROR code.
//
// resourceLabel goes into both the span message and the user-facing message
// ("jurisdiction not found" / "employee not found"). Callers supply opName
// so the error wraps the originating method name for traceability.
func mapDownstreamErr(ctx context.Context, span trace.Span, logger *tracerobs.OTelLogger, serviceName, opName, resourceLabel string, err error) error {
	if errors.Is(err, errors.ErrNotFound) {
		span.SetStatus(codes.Ok, resourceLabel+" not found")
		return errors.ErrNotFound.WithDescription(resourceLabel + " not found")
	}
	return failOp(ctx, span, logger, serviceName, errors.CodeDatabase,
		"failed to "+opName+" "+resourceLabel, err, nil)
}

// publishMutationEvent wraps the spanForEvent + clientID-from-ctx ceremony
// that precedes every PubSub publish. Topic, action, tenantID, and the
// payload vary; everything else is identical at every call site.
func publishMutationEvent(ctx context.Context, ep *pubsub.EventPublisher, topic, action, tenantID string, payload interface{}, count int) {
	spanForEvent := trace.SpanFromContext(ctx)
	clientID, _ := ctx.Value("clientId").(string)
	ep.PublishEvent(ctx, spanForEvent, topic, action, tenantID, clientID, payload, count)
}

// propagateOp is the counterpart to failOp for errors that already carry the
// correct domain code — typically those returned by validators or other
// helpers that classify their own failures (e.g. validateUserID returns
// DOWNSTREAM_ERROR when Keycloak is unreachable and INVALID_REQUEST when the
// referenced user does not exist).
//
// Unlike failOp, this does NOT re-wrap the error or override its code: the
// caller's hard-coded category would mis-classify network-level dependency
// failures as client-side validation errors. Span/metric/log observability
// still fires using the inner error's code; the original err is returned
// unchanged so the handler maps it to the appropriate HTTP status.
func propagateOp(ctx context.Context, span trace.Span, logger *tracerobs.OTelLogger, serviceName, msg string, err error, fields map[string]interface{}) error {
	code := errors.CodeInternal
	if e, ok := err.(*errors.Error); ok {
		code = e.Code
	}
	span.RecordError(err)
	span.SetStatus(codes.Error, msg)
	tracerobs.RecordError(ctx, code, serviceName)
	logger.ErrorWithTrace(ctx, err, msg, fields)
	return err
}
