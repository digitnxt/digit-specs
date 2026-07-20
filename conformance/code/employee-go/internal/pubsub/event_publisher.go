package pubsub

import (
	"context"
	"employee/internal/config"
	"time"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	tracerpubsub "github.com/digitnxt/digit3/src/libraries/tracer/pubsub"
	"go.opentelemetry.io/otel/trace"
)

// EventPublisher handles publishing events
type EventPublisher struct {
	pubsubClient tracerpubsub.PubSubClient
	config       *config.Config
	logger       *tracerobs.OTelLogger
}

// NewEventPublisher creates a new event publisher
func NewEventPublisher(pubsubClient tracerpubsub.PubSubClient, cfg *config.Config) *EventPublisher {
	return &EventPublisher{
		pubsubClient: pubsubClient,
		config:       cfg,
		logger:       tracerobs.GetOTelLogger(),
	}
}

// PublishEvent publishes any event to the specified topic
// Generic function for all event types
// Retry logic is handled by the tracer library's Publish() method
func (ep *EventPublisher) PublishEvent(ctx context.Context, span trace.Span, topic string, eventType string, tenantID, clientID string, data interface{}, count int) error {
	if !ep.shouldPublish() {
		return nil
	}

	// Build event with standard fields
	traceID := ""
	if span != nil && span.SpanContext().TraceID().IsValid() {
		traceID = span.SpanContext().TraceID().String()
	}

	event := map[string]interface{}{
		"eventType": eventType,
		"eventTime": time.Now().UnixMilli(),
		"tenantId":  tenantID,
		"clientId":  clientID,
		"traceId":   traceID,
		"data":      data,
	}

	if err := ep.pubsubClient.Publish(topic, event); err != nil {
		ep.logger.ErrorWithTrace(ctx, err, "Failed to publish "+eventType+" event", map[string]interface{}{
			"tenantId": tenantID,
			"topic":    topic,
			"event":    eventType,
		})
		// Don't fail the request, just log the error (graceful degradation)
		// Retry logic is handled by tracer library
		return nil
	}

	ep.logger.InfoWithTrace(ctx, "Published "+eventType+" event", map[string]interface{}{
		"tenantId": tenantID,
		"topic":    topic,
		"count":    count,
	})

	return nil
}

// shouldPublish checks if publishing is enabled
func (ep *EventPublisher) shouldPublish() bool {
	return ep.pubsubClient != nil && ep.config.PubSub.Enabled
}
