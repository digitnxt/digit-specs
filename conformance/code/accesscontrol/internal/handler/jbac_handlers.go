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
	"accesscontrol/internal/util"
	"accesscontrol/internal/validator"

	"github.com/gin-gonic/gin"
	"github.com/rs/zerolog/log"
)

func (h *Handlers) CreateJbacRule(c *gin.Context) {
	var req model.CreateJbacRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Warn().Err(err).Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("invalid request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to parse JSON request body"))
		return
	}

	if valid, validationErrs := validator.ValidateJbacRequest(&req); !valid {
		log.Warn().Strs("errors", validationErrs).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("JBAC rule validation failed")
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")
	ctx := context.WithValue(c.Request.Context(), model.UserIDContextKey, c.GetHeader("X-User-ID"))
	ctx = context.WithValue(ctx, model.RequestIDContextKey, c.GetHeader("X-Request-ID"))

	rule, err := h.service.CreateJbacRule(ctx, tenantID, &req)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Msg("failed to create JBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while creating the JBAC rule"))
		return
	}

	c.JSON(http.StatusCreated, &model.JbacRuleResponse{Rule: rule})
}

func (h *Handlers) GetJbacRule(c *gin.Context) {
	id := c.Param("id")

	if validationErrs := validator.ValidateRuleID(id); len(validationErrs) > 0 {
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")

	rule, err := h.service.GetJbacRule(c.Request.Context(), tenantID, id)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			log.Warn().Str("tenantID", tenantID).Str("ruleID", id).Msg("JBAC rule not found")
			c.JSON(http.StatusNotFound, model.Errors("AccessControl.NotFound", "No JBAC rule found with the specified ID for this tenant"))
			return
		}
		log.Error().Err(err).Str("tenantID", tenantID).Str("ruleID", id).Msg("failed to retrieve JBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving the JBAC rule"))
		return
	}

	c.JSON(http.StatusOK, &model.JbacRuleResponse{Rule: rule})
}

func (h *Handlers) UpdateJbacRule(c *gin.Context) {
	id := c.Param("id")

	if validationErrs := validator.ValidateRuleID(id); len(validationErrs) > 0 {
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	// Read body once for null-rejection pre-pass + typed unmarshal.
	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		log.Warn().Err(err).Msg("failed to read request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to read request body"))
		return
	}

	// Reject explicit JSON null on fields that are not nullable.
	if nullErrs := util.RejectExplicitNulls(body, model.JbacNonNullableUpdateFields); len(nullErrs) > 0 {
		log.Warn().Strs("errors", nullErrs).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("JBAC PATCH rejected: null on non-nullable field")
		c.JSON(http.StatusBadRequest, model.ValidationErrors(nullErrs))
		return
	}

	var req model.UpdateJbacRuleRequest
	if err := json.Unmarshal(body, &req); err != nil {
		log.Warn().Err(err).Str("method", c.Request.Method).Str("path", c.Request.URL.Path).Msg("invalid request body")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Failed to parse JSON request body"))
		return
	}

	if valid, validationErrs := validator.ValidateJbacRequest(&req); !valid {
		log.Warn().Strs("errors", validationErrs).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("JBAC rule validation failed")
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")
	ctx := context.WithValue(c.Request.Context(), model.UserIDContextKey, c.GetHeader("X-User-ID"))
	ctx = context.WithValue(ctx, model.RequestIDContextKey, c.GetHeader("X-Request-ID"))

	rule, err := h.service.UpdateJbacRule(ctx, tenantID, id, &req)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			log.Warn().Str("tenantID", tenantID).Str("ruleID", id).Msg("JBAC rule not found")
			c.JSON(http.StatusNotFound, model.Errors("AccessControl.NotFound", "No JBAC rule found with the specified ID for this tenant"))
			return
		}
		log.Error().Err(err).Str("tenantID", tenantID).Str("ruleID", id).Msg("failed to update JBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while updating the JBAC rule"))
		return
	}

	c.JSON(http.StatusOK, &model.JbacRuleResponse{Rule: rule})
}

func (h *Handlers) DeleteJbacRule(c *gin.Context) {
	id := c.Param("id")

	if validationErrs := validator.ValidateRuleID(id); len(validationErrs) > 0 {
		c.JSON(http.StatusBadRequest, model.ValidationErrors(validationErrs))
		return
	}

	tenantID := c.GetHeader("X-Tenant-ID")

	err := h.service.DeleteJbacRule(c.Request.Context(), tenantID, id)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			log.Warn().Str("tenantID", tenantID).Str("ruleID", id).Msg("JBAC rule not found for deletion")
			c.JSON(http.StatusNotFound, model.Errors("AccessControl.NotFound", "No JBAC rule found with the specified ID for this tenant"))
			return
		}
		log.Error().Err(err).Str("tenantID", tenantID).Str("ruleID", id).Msg("failed to delete JBAC rule")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while deleting the JBAC rule"))
		return
	}

	c.Status(http.StatusNoContent)
}

func (h *Handlers) ListJbacRules(c *gin.Context) {
	tenantID := c.GetHeader("X-Tenant-ID")

	var filters model.JbacRulesFilter
	if err := c.ShouldBindQuery(&filters); err != nil {
		log.Warn().Err(err).Str("tenantID", tenantID).Msg("invalid query parameters for list JBAC rules")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Invalid query parameter: "+err.Error()))
		return
	}
	if filters.Limit == 0 {
		filters.Limit = 50
	}

	response, err := h.service.ListJbacRules(c.Request.Context(), tenantID, filters)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Msg("failed to list JBAC rules")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving JBAC rules"))
		return
	}

	c.JSON(http.StatusOK, response)
}

func (h *Handlers) ListAllJbacRules(c *gin.Context) {
	var filters model.AllRulesFilter
	if err := c.ShouldBindQuery(&filters); err != nil {
		log.Warn().Err(err).Msg("invalid query parameters for list all JBAC rules")
		c.JSON(http.StatusBadRequest, model.Errors("AccessControl.InvalidRequest", "Invalid query parameter: "+err.Error()))
		return
	}
	if filters.Limit == 0 {
		filters.Limit = 100
	}

	response, err := h.service.ListAllJbacRules(c.Request.Context(), filters)
	if err != nil {
		log.Error().Err(err).Msg("failed to list all JBAC rules")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving all JBAC rules"))
		return
	}

	c.JSON(http.StatusOK, response)
}

func (h *Handlers) GetAllJbacRulesVersion(c *gin.Context) {
	version, err := h.service.GetAllJbacRulesVersion(c.Request.Context())
	if err != nil {
		log.Error().Err(err).Msg("failed to compute JBAC rules version hash")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while retrieving the JBAC rules version"))
		return
	}

	c.JSON(http.StatusOK, gin.H{"version": version})
}

func (h *Handlers) BulkCreateJbacRules(c *gin.Context) {
	var req model.BulkCreateJbacRulesRequest
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
		if valid, validationErrs := validator.ValidateJbacRequest(&req.Rules[i]); !valid {
			// Prefix the rule index so the caller can locate the failing entry
			// in their batch instead of guessing.
			indexed := make([]string, len(validationErrs))
			for j, e := range validationErrs {
				indexed[j] = fmt.Sprintf("rules[%d]: %s", i, e)
			}
			log.Warn().Strs("errors", indexed).Str("tenantID", c.GetHeader("X-Tenant-ID")).Msg("JBAC rule validation failed")
			c.JSON(http.StatusBadRequest, model.ValidationErrors(indexed))
			return
		}
	}

	tenantID := c.GetHeader("X-Tenant-ID")
	ctx := context.WithValue(c.Request.Context(), model.UserIDContextKey, c.GetHeader("X-User-ID"))
	ctx = context.WithValue(ctx, model.RequestIDContextKey, c.GetHeader("X-Request-ID"))

	response, err := h.service.BulkCreateJbacRules(ctx, tenantID, req.Rules)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Int("ruleCount", len(req.Rules)).Msg("failed to bulk create JBAC rules")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while creating the JBAC rules"))
		return
	}

	c.JSON(http.StatusCreated, response)
}

func (h *Handlers) DeleteJbacRulesByTenant(c *gin.Context) {
	tenantID := c.GetHeader("X-Tenant-ID")

	err := h.service.DeleteJbacRulesByTenant(c.Request.Context(), tenantID)
	if err != nil {
		log.Error().Err(err).Str("tenantID", tenantID).Msg("failed to delete JBAC rules by tenant")
		c.JSON(http.StatusInternalServerError, model.Errors("AccessControl.InternalError", "An internal error occurred while deleting the JBAC rules"))
		return
	}

	c.Status(http.StatusNoContent)
}
