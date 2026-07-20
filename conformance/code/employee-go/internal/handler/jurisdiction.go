package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"

	"employee/internal/httputil"
	"employee/internal/models"
	"employee/internal/service"
	"employee/pkg/errors"
)

type JurisdictionHandler struct {
	service service.JurisdictionService
	logger  *logrus.Logger
}

func NewJurisdictionHandler(service service.JurisdictionService, logger *logrus.Logger) *JurisdictionHandler {
	if logger == nil {
		logger = logrus.New()
	}
	return &JurisdictionHandler{
		service: service,
		logger:  logger,
	}
}

func (h *JurisdictionHandler) SearchJurisdictions(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.jurisdiction.search")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	employeeID, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}

	criteria := &models.JurisdictionSearchCriteria{}
	if err := c.ShouldBindQuery(criteria); err != nil {
		// Wrap the raw Gin/Go parse error as a typed INVALID_REQUEST so
		// StatusForCode maps it to 400 (an untyped error defaults to 500 in
		// FailService). Mirrors SearchEmployees.
		wrapped := errors.New(errors.CodeInvalidRequest, err.Error())
		httputil.FailService(c, span, h.logger, wrapped, "Invalid query parameters")
		return
	}

	span.SetAttributes(attribute.String("employee.id", employeeID))

	jurisdictions, err := h.service.SearchJurisdictions(ctx, employeeID, criteria)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to search jurisdictions")
		return
	}

	span.SetStatus(codes.Ok, "Jurisdictions searched")
	c.JSON(http.StatusOK, jurisdictions)
}

func (h *JurisdictionHandler) GetJurisdictionByUUID(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.jurisdiction.get_by_uuid")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	employeeID, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}
	jurisdictionID, ok := httputil.RequireUUIDParam(c, span, h.logger, "jurisdictionId", "jurisdiction")
	if !ok {
		return
	}
	span.SetAttributes(
		attribute.String("employee.id", employeeID),
		attribute.String("jurisdiction.uuid", jurisdictionID),
	)

	jurisdiction, err := h.service.GetJurisdictionByUUID(ctx, employeeID, jurisdictionID)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to get jurisdiction")
		return
	}

	span.SetStatus(codes.Ok, "Jurisdiction retrieved")
	c.JSON(http.StatusOK, jurisdiction)
}

func (h *JurisdictionHandler) CreateJurisdiction(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.jurisdiction.create")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	employeeID, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}

	var req models.CreateJurisdictionRequest
	if !httputil.BindBody(c, span, h.logger, &req) {
		return
	}
	span.SetAttributes(attribute.String("employee.id", employeeID))

	jurisdiction, err := h.service.CreateJurisdiction(ctx, employeeID, &req)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to create jurisdiction")
		return
	}

	span.SetStatus(codes.Ok, "Jurisdiction created")
	c.JSON(http.StatusCreated, jurisdiction)
}

func (h *JurisdictionHandler) UpdateJurisdiction(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.jurisdiction.update")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	employeeID, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}
	jurisdictionID, ok := httputil.RequireUUIDParam(c, span, h.logger, "jurisdictionId", "jurisdiction")
	if !ok {
		return
	}

	var req models.UpdateJurisdictionRequest
	if !httputil.BindBody(c, span, h.logger, &req) {
		return
	}
	span.SetAttributes(
		attribute.String("employee.id", employeeID),
		attribute.String("jurisdiction.uuid", jurisdictionID),
	)

	jurisdiction, err := h.service.UpdateJurisdiction(ctx, employeeID, jurisdictionID, &req)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to update jurisdiction")
		return
	}

	span.SetStatus(codes.Ok, "Jurisdiction updated")
	c.JSON(http.StatusOK, jurisdiction)
}
