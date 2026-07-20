package observability

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

func getTenantFromContext(ctx context.Context) string {
	if tenantID, ok := ctx.Value("tenantId").(string); ok && tenantID != "" {
		return tenantID
	}
	return "unknown"
}

var (
	employeesCreated      metric.Int64Counter
	employeesSearched     metric.Int64Counter
	employeesUpdated      metric.Int64Counter
	employeesDeleted      metric.Int64Counter
	employeesDeactivated  metric.Int64Counter
	employeesReactivated  metric.Int64Counter
	jurisdictionsCreated  metric.Int64Counter
	jurisdictionsSearched metric.Int64Counter
	jurisdictionsUpdated  metric.Int64Counter
)

// InitializeBusinessMetrics creates employee-service-specific business metrics.
func InitializeBusinessMetrics() error {
	meter := otel.Meter("employee-service")

	var err error

	if employeesCreated, err = meter.Int64Counter(
		"employees_created_total",
		metric.WithDescription("Total number of employees created"),
	); err != nil {
		return err
	}

	if employeesSearched, err = meter.Int64Counter(
		"employees_searched_total",
		metric.WithDescription("Total number of employee searches performed"),
	); err != nil {
		return err
	}

	if employeesUpdated, err = meter.Int64Counter(
		"employees_updated_total",
		metric.WithDescription("Total number of employees updated"),
	); err != nil {
		return err
	}

	if employeesDeleted, err = meter.Int64Counter(
		"employees_deleted_total",
		metric.WithDescription("Total number of employees deleted"),
	); err != nil {
		return err
	}

	if employeesDeactivated, err = meter.Int64Counter(
		"employees_deactivated_total",
		metric.WithDescription("Total number of employees deactivated"),
	); err != nil {
		return err
	}

	if employeesReactivated, err = meter.Int64Counter(
		"employees_reactivated_total",
		metric.WithDescription("Total number of employees reactivated"),
	); err != nil {
		return err
	}

	if jurisdictionsCreated, err = meter.Int64Counter(
		"jurisdictions_created_total",
		metric.WithDescription("Total number of jurisdictions created"),
	); err != nil {
		return err
	}

	if jurisdictionsSearched, err = meter.Int64Counter(
		"jurisdictions_searched_total",
		metric.WithDescription("Total number of jurisdiction searches performed"),
	); err != nil {
		return err
	}

	if jurisdictionsUpdated, err = meter.Int64Counter(
		"jurisdictions_updated_total",
		metric.WithDescription("Total number of jurisdictions updated"),
	); err != nil {
		return err
	}

	return nil
}

func attrs(tenantID, operation string) metric.MeasurementOption {
	return metric.WithAttributes(
		attribute.String("tenantId", tenantID),
		attribute.String("operation", operation),
	)
}

func RecordEmployeeCreated(ctx context.Context, tenantID string, count int) {
	employeesCreated.Add(ctx, int64(count), attrs(tenantID, "create"))
}

func RecordEmployeeCreatedFromContext(ctx context.Context, count int) {
	RecordEmployeeCreated(ctx, getTenantFromContext(ctx), count)
}

func RecordEmployeeSearched(ctx context.Context, tenantID string, resultCount int) {
	employeesSearched.Add(ctx, 1, metric.WithAttributes(
		attribute.String("tenantId", tenantID),
		attribute.String("operation", "search"),
		attribute.Int("result_count", resultCount),
	))
}

func RecordEmployeeSearchedFromContext(ctx context.Context, resultCount int) {
	RecordEmployeeSearched(ctx, getTenantFromContext(ctx), resultCount)
}

func RecordEmployeeUpdated(ctx context.Context, tenantID string, count int) {
	employeesUpdated.Add(ctx, int64(count), attrs(tenantID, "update"))
}

func RecordEmployeeUpdatedFromContext(ctx context.Context, count int) {
	RecordEmployeeUpdated(ctx, getTenantFromContext(ctx), count)
}

func RecordEmployeeDeleted(ctx context.Context, tenantID string) {
	employeesDeleted.Add(ctx, 1, attrs(tenantID, "delete"))
}

func RecordEmployeeDeletedFromContext(ctx context.Context) {
	RecordEmployeeDeleted(ctx, getTenantFromContext(ctx))
}

func RecordEmployeeDeactivated(ctx context.Context, tenantID string) {
	employeesDeactivated.Add(ctx, 1, attrs(tenantID, "deactivate"))
}

func RecordEmployeeDeactivatedFromContext(ctx context.Context) {
	RecordEmployeeDeactivated(ctx, getTenantFromContext(ctx))
}

func RecordEmployeeReactivated(ctx context.Context, tenantID string) {
	employeesReactivated.Add(ctx, 1, attrs(tenantID, "reactivate"))
}

func RecordEmployeeReactivatedFromContext(ctx context.Context) {
	RecordEmployeeReactivated(ctx, getTenantFromContext(ctx))
}

func RecordJurisdictionCreated(ctx context.Context, tenantID string) {
	jurisdictionsCreated.Add(ctx, 1, attrs(tenantID, "create"))
}

func RecordJurisdictionCreatedFromContext(ctx context.Context) {
	RecordJurisdictionCreated(ctx, getTenantFromContext(ctx))
}

func RecordJurisdictionSearched(ctx context.Context, tenantID string, resultCount int) {
	jurisdictionsSearched.Add(ctx, 1, metric.WithAttributes(
		attribute.String("tenantId", tenantID),
		attribute.String("operation", "search"),
		attribute.Int("result_count", resultCount),
	))
}

func RecordJurisdictionSearchedFromContext(ctx context.Context, resultCount int) {
	RecordJurisdictionSearched(ctx, getTenantFromContext(ctx), resultCount)
}

func RecordJurisdictionUpdated(ctx context.Context, tenantID string) {
	jurisdictionsUpdated.Add(ctx, 1, attrs(tenantID, "update"))
}

func RecordJurisdictionUpdatedFromContext(ctx context.Context) {
	RecordJurisdictionUpdated(ctx, getTenantFromContext(ctx))
}
