package handlers

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"individual/internal/common"
	"individual/internal/middleware"
	"individual/internal/models"
	"individual/internal/service"
	"individual/internal/validator"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/rs/zerolog/log"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
)

// sendError sends a standardized error response. All error responses are
// emitted as a []Error envelope for shape uniformity across status codes.
func sendError(c *gin.Context, statusCode int, code, message string) {
	c.JSON(statusCode, []models.Error{{Code: code, Message: message}})
}

// SearchIndividualsQuery handles GET /individuals — query-string driven search.
// All param parsing and validation is declared on IndividualSearchFilter via
// `form` / `binding` tags; ShouldBindQuery enforces them and returns a
// structured validator error that BindingErrors turns into a 400 []Error.
func (h *IndividualHandler) SearchIndividualsQuery(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.individual.search")
	defer span.End()

	reqContext := middleware.GetRequestContext(c)
	span.SetAttributes(attribute.String("tenant.id", reqContext.TenantID))

	var filter models.IndividualSearchFilter
	if err := c.ShouldBindQuery(&filter); err != nil {
		span.SetStatus(codes.Error, "invalid query params")
		c.JSON(http.StatusBadRequest, common.BindingErrors(err))
		return
	}

	// Defaults for pagination after binding. Page/Size are *int so explicit 0
	// is rejected by the binding (bug.md #14); nil here means "absent" and
	// we apply the defaults.
	page := common.DefaultPage
	if filter.Page != nil {
		page = *filter.Page
	}
	size := common.DefaultPageSize
	if filter.Size != nil {
		size = *filter.Size
	}

	req := models.IndividualSearchRequest{
		Individual:     filter.ToSearchCriteria(),
		Page:           page,
		Size:           size,
		IncludeDeleted: filter.IncludeDeleted,
	}

	individuals, total, err := h.service.SearchIndividuals(ctx, &req, reqContext.TenantID)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "search failed")
		handleServiceError(c, err)
		return
	}

	hasMore := int64(page)*int64(size) < total
	span.SetAttributes(attribute.Int64("result.total", total))
	span.SetStatus(codes.Ok, "")

	c.JSON(http.StatusOK, models.IndividualSearchResponse{
		TotalCount:  total,
		Page:        page,
		Size:        size,
		HasMore:     hasMore,
		Individuals: models.IndividualsFromEntities(individuals),
	})
}

// IndividualHandler handles HTTP requests for individuals. The handler owns
// request validation — it runs the validator before forwarding to the service.
type IndividualHandler struct {
	service   service.IndividualService
	validator validator.Validator
}

// NewIndividualHandler creates a new individual handler.
func NewIndividualHandler(service service.IndividualService, v validator.Validator) *IndividualHandler {
	return &IndividualHandler{service: service, validator: v}
}

func (h *IndividualHandler) CreateIndividualREST(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.individual.create")
	defer span.End()

	reqContext := middleware.GetRequestContext(c)
	span.SetAttributes(attribute.String("tenant.id", reqContext.TenantID))

	var dto models.IndividualDTO
	dec := json.NewDecoder(c.Request.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&dto); err != nil {
		log.Warn().Err(err).Str("path", c.Request.URL.Path).Msg("failed to decode request body")
		span.SetStatus(codes.Error, "invalid request body")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "Invalid request body: "+err.Error())
		return
	}

	individual := models.IndividualToEntity(&dto)
	individual.TenantID = reqContext.TenantID

	if err := h.validator.ValidateCreate(ctx, individual); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "validation failed")
		handleServiceError(c, err)
		return
	}

	created, err := h.service.CreateIndividual(ctx, individual, reqContext)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "create failed")
		handleServiceError(c, err)
		return
	}

	span.SetAttributes(attribute.String("individual.id", created.ID))
	span.SetStatus(codes.Ok, "")
	location := fmt.Sprintf("/individuals/v3/individuals/%s", created.ID)
	c.Header("Location", location)
	c.JSON(http.StatusCreated, models.IndividualFromEntity(created))
}

func (h *IndividualHandler) GetIndividual(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.individual.get")
	defer span.End()

	id := c.Param("id")
	if strings.TrimSpace(id) == "" {
		span.SetStatus(codes.Error, "missing id")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "ID is required")
		return
	}
	if _, err := uuid.Parse(id); err != nil {
		span.SetStatus(codes.Error, "invalid uuid")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "ID must be a valid UUID")
		return
	}

	reqContext := middleware.GetRequestContext(c)
	span.SetAttributes(
		attribute.String("tenant.id", reqContext.TenantID),
		attribute.String("individual.id", id),
	)

	// Prepare a search request scoped by the provided ID
	criteria := &models.SearchCriteria{ID: []string{id}}
	searchReq := models.IndividualSearchRequest{Individual: criteria, Page: 1, Size: 1, IncludeDeleted: false}

	individuals, _, err := h.service.SearchIndividuals(ctx, &searchReq, reqContext.TenantID)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "search failed")
		handleServiceError(c, err)
		return
	}
	if len(individuals) == 0 {
		span.SetStatus(codes.Error, "not found")
		sendError(c, http.StatusNotFound, common.ErrorNonExistentEntity, "Individual not found")
		return
	}
	span.SetStatus(codes.Ok, "")
	c.JSON(http.StatusOK, models.IndividualFromEntity(&individuals[0]))
}

func (h *IndividualHandler) UpdateIndividualPut(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.individual.update")
	defer span.End()

	id := c.Param("id")
	if strings.TrimSpace(id) == "" {
		span.SetStatus(codes.Error, "missing id")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "ID is required")
		return
	}
	if _, err := uuid.Parse(id); err != nil {
		span.SetStatus(codes.Error, "invalid uuid")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "ID must be a valid UUID")
		return
	}

	var dto models.IndividualDTO
	dec := json.NewDecoder(c.Request.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&dto); err != nil {
		log.Warn().Err(err).Str("path", c.Request.URL.Path).Msg("failed to decode request body")
		span.SetStatus(codes.Error, "invalid request body")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "Invalid request body: "+err.Error())
		return
	}

	reqContext := middleware.GetRequestContext(c)
	span.SetAttributes(
		attribute.String("tenant.id", reqContext.TenantID),
		attribute.String("individual.id", id),
	)
	individual := models.IndividualToEntity(&dto)
	individual.TenantID = reqContext.TenantID
	individual.ID = id

	if err := h.validator.ValidateUpdate(ctx, individual); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "validation failed")
		handleServiceError(c, err)
		return
	}

	updated, err := h.service.UpdateIndividual(ctx, individual, reqContext)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "update failed")
		handleServiceError(c, err)
		return
	}

	span.SetStatus(codes.Ok, "")
	c.JSON(http.StatusOK, models.IndividualFromEntity(updated))
}

func (h *IndividualHandler) DeleteIndividualByID(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.individual.delete")
	defer span.End()

	id := c.Param("id")
	if strings.TrimSpace(id) == "" {
		span.SetStatus(codes.Error, "missing id")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "ID is required")
		return
	}
	if _, err := uuid.Parse(id); err != nil {
		span.SetStatus(codes.Error, "invalid uuid")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "ID must be a valid UUID")
		return
	}

	reqContext := middleware.GetRequestContext(c)
	span.SetAttributes(
		attribute.String("tenant.id", reqContext.TenantID),
		attribute.String("individual.id", id),
	)
	individual := &models.Individual{ID: id, TenantID: reqContext.TenantID}

	if err := h.validator.ValidateDelete(ctx, individual); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "validation failed")
		handleServiceError(c, err)
		return
	}

	if _, err := h.service.DeleteIndividual(ctx, individual, reqContext); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "delete failed")
		handleServiceError(c, err)
		return
	}
	span.SetStatus(codes.Ok, "")
	c.Status(http.StatusNoContent)
}

// CheckIndividualExists implements GET /individuals/exists.
// Existence check is for a single individual: `id` and `individualId` are
// single-valued on this endpoint (the array form valid on search is rejected
// here by the IndividualExistsFilter struct's string field type).
// page/size are accepted but ignored per spec.
// At least one searchable filter (id, individualId, givenName, mobileNumber,
// gender, dateOfBirth) must be supplied — includeDeleted alone does not count.
func (h *IndividualHandler) CheckIndividualExists(c *gin.Context) {
	tracer := otel.Tracer("individual-handler")
	ctx, span := tracer.Start(c.Request.Context(), "handler.individual.exists")
	defer span.End()

	reqContext := middleware.GetRequestContext(c)
	span.SetAttributes(attribute.String("tenant.id", reqContext.TenantID))

	var filter models.IndividualExistsFilter
	if err := c.ShouldBindQuery(&filter); err != nil {
		span.SetStatus(codes.Error, "invalid query params")
		c.JSON(http.StatusBadRequest, common.BindingErrors(err))
		return
	}

	if !filter.HasFilter() {
		span.SetStatus(codes.Error, "no filter provided")
		sendError(c, http.StatusBadRequest, "VALIDATION_ERROR", "At least one filter parameter is required")
		return
	}

	exists, err := h.service.IndividualExists(ctx, filter.ToSearchCriteria(), reqContext.TenantID, filter.IncludeDeleted)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "exists check failed")
		handleServiceError(c, err)
		return
	}

	span.SetAttributes(attribute.Bool("result.exists", exists))
	span.SetStatus(codes.Ok, "")
	c.JSON(http.StatusOK, models.ExistsResponse{Exists: exists})
}

func (h *IndividualHandler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "UP",
		"service": "individual",
	})
}

// handleServiceError translates a service-layer error (typically a
// *common.CustomError) into the right HTTP status and response envelope.
// Package-level so config and individual handlers share the same mapping.
func handleServiceError(c *gin.Context, err error) {
	customErr, ok := err.(*common.CustomError)
	if !ok {
		// Unclassified error — this is a genuine internal fault, not a DB error.
		sendError(c, http.StatusInternalServerError, common.ErrorInternal, "Internal server error")
		return
	}

	httpStatus := http.StatusInternalServerError
	message := customErr.Message
	if message == "" {
		message = "An error occurred"
	}

	switch customErr.Code {
	case common.ErrorValidation:
		httpStatus = http.StatusBadRequest
	case common.ErrorNonExistentEntity:
		httpStatus = http.StatusNotFound
	case common.ErrorUniqueEntity, common.ErrorRowVersionMismatch, common.ErrorDuplicate:
		// DUPLICATE_ERROR (Postgres 23505) is detected and typed in the repository layer now,
		// so the handler only maps the code → 409 (no error-string inspection here).
		httpStatus = http.StatusConflict
	case common.ErrorDownstream:
		// A dependency (e.g. idgen) failed — not the client's fault.
		httpStatus = http.StatusBadGateway
	case common.ErrorDatabase:
		httpStatus = http.StatusInternalServerError
	}

	// Wire Error carries code/message/description only — no params (matching the
	// platform contract and Java individual). Caller-specific detail is already
	// promoted into message via CustomError.WithContext.
	apiError := models.Error{
		Code:    customErr.Code,
		Message: message,
	}
	if customErr.Description != "" {
		apiError.Description = customErr.Description
	}

	c.JSON(httpStatus, []models.Error{apiError})
}
