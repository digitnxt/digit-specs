package handlers

import (
	"encoding/json"
	"net/http"

	"individual/internal/middleware"
	"individual/internal/models"
	"individual/internal/service"
	"individual/internal/validator"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
)

// ConfigHandler handles HTTP requests for tenant validation config. The
// handler owns request validation — it runs the validator before forwarding
// to the service.
type ConfigHandler struct {
	service   service.ConfigService
	validator validator.Validator
}

func NewConfigHandler(svc service.ConfigService, v validator.Validator) *ConfigHandler {
	return &ConfigHandler{service: svc, validator: v}
}

func (h *ConfigHandler) Upsert(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.config.upsert")
	defer span.End()

	reqCtx := middleware.GetRequestContext(c)
	span.SetAttributes(attribute.String("tenant.id", reqCtx.TenantID))

	var dto models.ConfigDTO
	dec := json.NewDecoder(c.Request.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&dto); err != nil {
		span.SetStatus(codes.Error, "invalid request body")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "Invalid request body: "+err.Error())
		return
	}

	// Reject empty body — an empty config has no useful effect and was
	// silently being accepted as a 201. See bug.md #15.
	if dto.MobileRegex == "" && dto.NameRegex == "" && len(dto.UniquenessCriteria) == 0 {
		span.SetStatus(codes.Error, "empty config body")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR",
			"at least one of mobileRegex, nameRegex, uniquenessCriteria is required")
		return
	}

	entity := models.ConfigToEntity(&dto)

	if err := h.validator.ValidateConfig(entity); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "validation failed")
		handleServiceError(c, err)
		return
	}

	cfg, created, err := h.service.Upsert(ctx, reqCtx, entity)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "upsert failed")
		handleServiceError(c, err)
		return
	}

	span.SetAttributes(attribute.Bool("config.created", created))
	span.SetStatus(codes.Ok, "")
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	c.JSON(status, models.ConfigFromEntity(cfg))
}

// Get implements GET /configs — returns the tenant's active validation config,
// 404 if none has been set yet.
func (h *ConfigHandler) Get(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.config.get")
	defer span.End()

	reqCtx := middleware.GetRequestContext(c)
	span.SetAttributes(attribute.String("tenant.id", reqCtx.TenantID))

	cfg, err := h.service.GetByTenant(ctx, reqCtx.TenantID)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "get config failed")
		handleServiceError(c, err)
		return
	}
	if cfg == nil {
		span.SetStatus(codes.Error, "config not found")
		sendError(c, http.StatusNotFound, "NOT_FOUND", "No configuration found for this tenant")
		return
	}
	span.SetStatus(codes.Ok, "")
	c.JSON(http.StatusOK, models.ConfigFromEntity(cfg))
}
