package repository

import (
	"context"
	"time"

	"individual/internal/models"

	tenantdb "github.com/digitnxt/digit3/src/libraries/tenant-migration/tenantdb"
	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type ConfigRepository interface {
	GetByTenant(ctx context.Context, tenantID string) (*models.Config, error)
	GetByTenantForUpdate(ctx context.Context, tenantID string) (*models.Config, error)
	Insert(ctx context.Context, cfg *models.Config) error
	Update(ctx context.Context, cfg *models.Config) error
}

type configRepository struct {
	db *gorm.DB
}

func NewConfigRepository(db *gorm.DB) ConfigRepository {
	return &configRepository{db: db}
}

func (r *configRepository) GetByTenant(ctx context.Context, tenantID string) (*models.Config, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.config.getByTenant")
	defer span.End()
	span.SetAttributes(
		attribute.String("db.table", "individual_config_v3"),
		attribute.String("tenant.id", tenantID),
	)
	start := time.Now()

	var cfg models.Config
	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).Where("tenantid = ?", tenantID).First(&cfg).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_config_v3", duration, err == nil || err == gorm.ErrRecordNotFound)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to get config by tenant")
		return nil, err
	}
	span.SetStatus(codes.Ok, "")
	return &cfg, nil
}

// GetByTenantForUpdate is GetByTenant plus a row lock (SELECT ... FOR UPDATE) so
// a concurrent upsert for the same tenant serialises behind it. Used only by
// Upsert to make the read-modify-write (version bump) race-free; plain reads use
// GetByTenant. Relies on the per-request transaction (tenantdb middleware) so the
// lock is held until the request commits.
func (r *configRepository) GetByTenantForUpdate(ctx context.Context, tenantID string) (*models.Config, error) {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.config.getByTenantForUpdate")
	defer span.End()
	span.SetAttributes(attribute.String("tenant.id", tenantID))
	start := time.Now()

	var cfg models.Config
	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).
		Clauses(clause.Locking{Strength: "UPDATE"}).
		Where("tenantid = ?", tenantID).
		First(&cfg).Error

	tracerobs.RecordDBOperation(ctx, "SELECT", "individual_config_v3", time.Since(start), err == nil || err == gorm.ErrRecordNotFound)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			span.SetStatus(codes.Ok, "")
			return nil, nil
		}
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to lock config by tenant")
		return nil, err
	}
	span.SetStatus(codes.Ok, "")
	return &cfg, nil
}

// Insert creates a new config row. cfg.ID is populated by GORM after the call.
func (r *configRepository) Insert(ctx context.Context, cfg *models.Config) error {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.config.insert")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_config_v3"))
	start := time.Now()

	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).Create(cfg).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "INSERT", "individual_config_v3", duration, err == nil)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to insert config")
		return err
	}
	span.SetStatus(codes.Ok, "")
	return nil
}

// Update overwrites the row identified by cfg.ID with the values in cfg.
// Callers are responsible for preserving immutable fields (CreatedBy/CreatedTime)
// before calling.
func (r *configRepository) Update(ctx context.Context, cfg *models.Config) error {
	tracer := otel.Tracer("individual-repository")
	ctx, span := tracer.Start(ctx, "db.config.update")
	defer span.End()
	span.SetAttributes(attribute.String("db.table", "individual_config_v3"))
	start := time.Now()

	db := tenantdb.GetTenantDB(ctx, r.db)
	err := db.WithContext(ctx).Save(cfg).Error

	duration := time.Since(start)
	tracerobs.RecordDBOperation(ctx, "UPDATE", "individual_config_v3", duration, err == nil)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to update config")
		return err
	}
	span.SetStatus(codes.Ok, "")
	return nil
}
