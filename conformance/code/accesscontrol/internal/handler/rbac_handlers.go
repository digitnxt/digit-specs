package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"accesscontrol/internal/constants"
	"accesscontrol/internal/model"
	"accesscontrol/internal/repository"
	"accesscontrol/internal/service"
	"accesscontrol/internal/util"
	"accesscontrol/internal/validator"

	"github.com/gin-gonic/gin"
	"github.com/rs/zerolog/log"
)

// Handlers holds the service for the handlers
type Handlers struct {
	service service.Service
}

// NewHandlers creates a new Handlers
func NewHandlers(service service.Service) *Handlers {
	return &Handlers{service: service}
}

func (h *Handlers) CreateRbacRule(c *gin.Context) {
	var req model.CreateRbacRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Warn().Err(err).Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("invalid request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to parse JSON request body"))
		return
	}

	req.ApplyDefaults()

	if valid, validationErrs := validator.ValidateRbacRequest(&req); !valid {
		log.Warn().Strs("errors", validationErrs).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("RBAC rule validation failed")
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")
	ctx := context.WithValue(c.Request.Context(), model.UserIDContextKey, c.GetHeader("X-User-ID"))
	ctx = context.WithValue(ctx, model.RequestIDContextKey, c.GetHeader("X-Request-ID"))

	rule, err := h.service.CreateRbacRule(ctx, tenantID, &req)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Msg("failed to create RBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while creating the RBAC rule"))
		return
	}

	c.JSON(http.StatusCreated, &model.RbacRuleResponse{Rule: rule})
}

func (h *Handlers) GetRbacRule(c *gin.Context) {
	id := c.Param("id")

	if validationErrs := validator.ValidateRuleID(id); len(validationErrs) > 0 {
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")

	rule, err := h.service.GetRbacRule(c.Request.Context(), tenantID, id)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			log.Warn().Str("tenantID", tenantID).Str("ruleID", id).Msg("RBAC rule not found")
			c.JSON(http.StatusNotFound, model.Errors("AccessControl.NotFound", "No RBAC rule found with the specified ID for this tenant"))
			return
		}
		log.Error().Err(err).Str("tenantID", tenantID).Str("ruleID", id).Msg("failed to retrieve RBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving the RBAC rule"))
		return
	}

	c.JSON(http.StatusOK, &model.RbacRuleResponse{Rule: rule})
}

func (h *Handlers) UpdateRbacRule(c *gin.Context) {
	id := c.Param("id")

	if validationErrs := validator.ValidateRuleID(id); len(validationErrs) > 0 {
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	// Read the body once so we can run the null-rejection pre-pass and then
	// the typed unmarshal. We cannot use c.ShouldBindJSON here because it
	// consumes the body.
	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		log.Warn().Err(err).Msg("failed to read request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to read request body"))
		return
	}

	// Reject explicit JSON null on fields that are not nullable.
	if nullErrs := util.RejectExplicitNulls(body, model.RbacNonNullableUpdateFields); len(nullErrs) > 0 {
		log.Warn().Strs("errors", nullErrs).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("RBAC PATCH rejected: null on non-nullable field")
		c.JSON(http.StatusBadRequest, model.ValidationErrors(nullErrs))
		return
	}

	var req model.UpdateRbacRuleRequest
	if err := json.Unmarshal(body, &req); err != nil {
		log.Warn().Err(err).Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("invalid request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to parse JSON request body"))
		return
	}

	if valid, validationErrs := validator.ValidateRbacRequest(&req); !valid {
		log.Warn().Strs("errors", validationErrs).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("RBAC rule validation failed")
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")
	ctx := context.WithValue(c.Request.Context(), model.UserIDContextKey, c.GetHeader("X-User-ID"))
	ctx = context.WithValue(ctx, model.RequestIDContextKey, c.GetHeader("X-Request-ID"))

	rule, err := h.service.UpdateRbacRule(ctx, tenantID, id, &req)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			log.Warn().Str("tenantID", tenantID).Str("ruleID", id).Msg("RBAC rule not found")
			c.JSON(http.StatusNotFound, model.Errors("AccessControl.NotFound", "No RBAC rule found with the specified ID for this tenant"))
			return
		}
		log.Error().Err(err).Str("tenantID", tenantID).Str("ruleID", id).Msg("failed to update RBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while updating the RBAC rule"))
		return
	}

	c.JSON(http.StatusOK, &model.RbacRuleResponse{Rule: rule})
}

func (h *Handlers) DeleteRbacRule(c *gin.Context) {
	id := c.Param("id")

	if validationErrs := validator.ValidateRuleID(id); len(validationErrs) > 0 {
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")

	err := h.service.DeleteRbacRule(c.Request.Context(), tenantID, id)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			log.Warn().Str("tenantID", tenantID).Str("ruleID", id).Msg("RBAC rule not found for deletion")
			c.JSON(http.StatusNotFound, model.Errors("AccessControl.NotFound", "No RBAC rule found with the specified ID for this tenant"))
			return
		}
		log.Error().Err(err).Str("tenantID", tenantID).Str("ruleID", id).Msg("failed to delete RBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while deleting the RBAC rule"))
		return
	}

	c.Status(http.StatusNoContent)
}

func (h *Handlers) ListRbacRules(c *gin.Context) {
	tenantID := c.GetHeader("X-Tenant-ID")

	var filters model.RbacRulesFilter
	if err := c.ShouldBindQuery(&filters); err != nil {
		log.Warn().Err(err).Str("tenantID", tenantID).Msg("invalid query parameters for list RBAC rules")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Invalid query parameter: "+err.Error()))
		return
	}
	if filters.Limit == 0 {
		filters.Limit = 50
	}

	response, err := h.service.ListRbacRules(c.Request.Context(), tenantID, filters)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Msg("failed to list RBAC rules")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving RBAC rules"))
		return
	}

	c.JSON(http.StatusOK, response)
}

func (h *Handlers) ListAllRbacRules(c *gin.Context) {
	var filters model.AllRulesFilter
	if err := c.ShouldBindQuery(&filters); err != nil {
		log.Warn().Err(err).Msg("invalid query parameters for list all RBAC rules")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Invalid query parameter: "+err.Error()))
		return
	}
	if filters.Limit == 0 {
		filters.Limit = 100
	}

	response, err := h.service.ListAllRbacRules(c.Request.Context(), filters)
	if err != nil {
		log.Error().Err(err).Msg("failed to list all RBAC rules")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving all RBAC rules"))
		return
	}

	c.JSON(http.StatusOK, response)
}

func (h *Handlers) GetAllRbacRulesVersion(c *gin.Context) {
	version, err := h.service.GetAllRbacRulesVersion(c.Request.Context())
	if err != nil {
		log.Error().Err(err).Msg("failed to compute RBAC rules version hash")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving the RBAC rules version"))
		return
	}

	c.JSON(http.StatusOK, gin.H{"version": version})
}

func (h *Handlers) BulkCreateRbacRules(c *gin.Context) {
	var req model.BulkCreateRbacRulesRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Warn().Err(err).Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("invalid request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to parse JSON request body"))
		return
	}

	if len(req.Rules) == 0 {
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "rules must be a non-empty array"))
		return
	}
	if len(req.Rules) > constants.MaxBulkRulesPerRequest {
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", fmt.Sprintf("rules must contain at most %d entries (got %d)", constants.MaxBulkRulesPerRequest, len(req.Rules))))
		return
	}

	for i := range req.Rules {
		req.Rules[i].ApplyDefaults()
		if valid, validationErrs := validator.ValidateRbacRequest(&req.Rules[i]); !valid {
			// Prefix the rule index so the caller can locate the failing entry
			// in their batch instead of guessing.
			indexed := make([]string, len(validationErrs))
			for j, e := range validationErrs {
				indexed[j] = fmt.Sprintf("rules[%d]: %s", i, e)
			}
			log.Warn().Strs("errors", indexed).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("RBAC rule validation failed")
			c.JSON(http.StatusBadRequest, model.ValidationErrors(indexed))
			return
		}
	}

	tenantID := c.GetHeader("X-Tenant-ID")
	ctx := context.WithValue(c.Request.Context(), model.UserIDContextKey, c.GetHeader("X-User-ID"))
	ctx = context.WithValue(ctx, model.RequestIDContextKey, c.GetHeader("X-Request-ID"))

	response, err := h.service.BulkCreateRbacRules(ctx, tenantID, req.Rules)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Int("ruleCount", len(req.Rules)).Msg("failed to bulk create RBAC rules")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while creating the RBAC rules"))
		return
	}

	c.JSON(http.StatusCreated, response)
}

func (h *Handlers) DeleteRbacRulesByTenant(c *gin.Context) {
	tenantID := c.GetHeader("X-Tenant-ID")

	err := h.service.DeleteRbacRulesByTenant(c.Request.Context(), tenantID)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Msg("failed to delete RBAC rules by tenant")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while deleting the RBAC rules"))
		return
	}

	c.Status(http.StatusNoContent)
}
