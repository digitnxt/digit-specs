package service

import (
	"context"
	"time"

	"individual/internal/common"
	"individual/internal/config"
	"individual/internal/models"
	"individual/internal/pubsub"
	"individual/internal/repository"
	"individual/pkg/observability"

	tracerobs "github.com/digitnxt/digit3/src/libraries/tracer/observability"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

// ConfigService persists per-tenant validation configuration. Validation is
// owned by the handler layer — Upsert assumes input has already passed the
// validator before reaching here.
type ConfigService interface {
	// Upsert inserts a new tenant config or replaces the existing one. Returns
	// the persisted config and `created=true` for inserts, `created=false`
	// for updates.
	Upsert(ctx context.Context, reqCtx *models.RequestContext, body *models.Config) (cfg *models.Config, created bool, err error)
	GetByTenant(ctx context.Context, tenantID string) (*models.Config, error)
}

type configService struct {
	repo           repository.ConfigRepository
	eventPublisher *pubsub.EventPublisher
	config         *config.Config
}

func NewConfigService(repo repository.ConfigRepository, eventPublisher *pubsub.EventPublisher, cfg *config.Config) ConfigService {
	return &configService{
		repo:           repo,
		eventPublisher: eventPublisher,
		config:         cfg,
	}
}

func (s *configService) Upsert(ctx context.Context, reqCtx *models.RequestContext, body *models.Config) (*models.Config, bool, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.config.upsert")
	defer span.End()
	span.SetAttributes(attribute.String("tenant.id", reqCtx.TenantID))

	body.TenantID = reqCtx.TenantID
	// Lock the tenant's config row for the rest of this request's transaction so
	// a concurrent upsert can't read the same version and clobber this one —
	// keeps the version counter honest. Last-write-wins on content is intended.
	existing, err := s.repo.GetByTenantForUpdate(ctx, reqCtx.TenantID)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to get existing config")
		tracerobs.RecordError(ctx, "config_upsert_get_error", "individual-service")
		return nil, false, common.ErrDatabase.WithParams(map[string]interface{}{
			"operation": "config-get",
			"error":     err.Error(),
		})
	}

	now := time.Now().UnixMilli()
	created := existing == nil

	if created {
		body.CreatedBy = reqCtx.UserID
		body.ModifiedBy = reqCtx.UserID
		body.CreatedTime = now
		body.ModifiedTime = now
		body.Version = 1
		body.RequestID = reqCtx.RequestID
		if err := s.repo.Insert(ctx, body); err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "failed to insert config")
			tracerobs.RecordError(ctx, "config_insert_error", "individual-service")
			return nil, false, common.ErrDatabase.WithParams(map[string]interface{}{
				"operation": "config-insert",
				"error":     err.Error(),
			})
		}
	} else {
		// Preserve immutable audit; bump version.
		body.ID = existing.ID
		body.CreatedBy = existing.CreatedBy
		body.CreatedTime = existing.CreatedTime
		body.ModifiedBy = reqCtx.UserID
		body.ModifiedTime = now
		body.Version = existing.Version + 1
		body.RequestID = reqCtx.RequestID
		if err := s.repo.Update(ctx, body); err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "failed to update config")
			tracerobs.RecordError(ctx, "config_update_error", "individual-service")
			return nil, false, common.ErrDatabase.WithParams(map[string]interface{}{
				"operation": "config-update",
				"error":     err.Error(),
			})
		}
	}

	observability.RecordConfigUpserted(ctx, reqCtx.TenantID, created)
	span.SetAttributes(attribute.Bool("config.created", created))
	span.SetStatus(codes.Ok, "")

	pubSpan := trace.SpanFromContext(ctx)
	s.eventPublisher.PublishEvent(ctx, pubSpan, s.config.PubSub.Topics.UpsertConfig, s.config.PubSub.Topics.UpsertConfig, reqCtx.TenantID, reqCtx.UserID, body, body.Version)

	return body, created, nil
}

func (s *configService) GetByTenant(ctx context.Context, tenantID string) (*models.Config, error) {
	tracer := otel.Tracer("individual-service")
	ctx, span := tracer.Start(ctx, "service.config.getByTenant")
	defer span.End()
	span.SetAttributes(attribute.String("tenant.id", tenantID))

	cfg, err := s.repo.GetByTenant(ctx, tenantID)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "failed to get config")
		tracerobs.RecordError(ctx, "config_get_error", "individual-service")
		return nil, err
	}
	span.SetStatus(codes.Ok, "")
	return cfg, nil
}
