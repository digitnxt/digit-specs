package observability

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

var (
	individualsCreated  metric.Int64Counter
	individualsSearched metric.Int64Counter
	individualsUpdated  metric.Int64Counter
	individualsDeleted  metric.Int64Counter
	configsUpserted     metric.Int64Counter
)

// InitializeBusinessMetrics creates individual-specific business metrics
func InitializeBusinessMetrics() error {
	meter := otel.Meter("individual-service")

	var err error

	individualsCreated, err = meter.Int64Counter(
		"individuals_created_total",
		metric.WithDescription("Total number of individuals created"),
	)
	if err != nil {
		return err
	}

	individualsSearched, err = meter.Int64Counter(
		"individuals_searched_total",
		metric.WithDescription("Total number of individual searches performed"),
	)
	if err != nil {
		return err
	}

	individualsUpdated, err = meter.Int64Counter(
		"individuals_updated_total",
		metric.WithDescription("Total number of individuals updated"),
	)
	if err != nil {
		return err
	}

	individualsDeleted, err = meter.Int64Counter(
		"individuals_deleted_total",
		metric.WithDescription("Total number of individuals deleted (soft)"),
	)
	if err != nil {
		return err
	}

	configsUpserted, err = meter.Int64Counter(
		"configs_upserted_total",
		metric.WithDescription("Total number of tenant configs upserted"),
	)
	if err != nil {
		return err
	}

	return nil
}

func RecordIndividualCreated(ctx context.Context, tenantID string, count int) {
	attrs := []attribute.KeyValue{
		attribute.String("tenantId", tenantID),
		attribute.String("operation", "create"),
	}
	individualsCreated.Add(ctx, int64(count), metric.WithAttributes(attrs...))
}

func RecordIndividualSearched(ctx context.Context, tenantID string, resultCount int) {
	attrs := []attribute.KeyValue{
		attribute.String("tenantId", tenantID),
		attribute.String("operation", "search"),
		attribute.Int("result_count", resultCount),
	}
	individualsSearched.Add(ctx, 1, metric.WithAttributes(attrs...))
}

func RecordIndividualUpdated(ctx context.Context, tenantID string, count int) {
	attrs := []attribute.KeyValue{
		attribute.String("tenantId", tenantID),
		attribute.String("operation", "update"),
	}
	individualsUpdated.Add(ctx, int64(count), metric.WithAttributes(attrs...))
}

func RecordIndividualDeleted(ctx context.Context, tenantID string, count int) {
	attrs := []attribute.KeyValue{
		attribute.String("tenantId", tenantID),
		attribute.String("operation", "delete"),
	}
	individualsDeleted.Add(ctx, int64(count), metric.WithAttributes(attrs...))
}

func RecordConfigUpserted(ctx context.Context, tenantID string, created bool) {
	op := "update"
	if created {
		op = "create"
	}
	attrs := []attribute.KeyValue{
		attribute.String("tenantId", tenantID),
		attribute.String("operation", op),
	}
	configsUpserted.Add(ctx, 1, metric.WithAttributes(attrs...))
}
