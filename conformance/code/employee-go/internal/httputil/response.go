package httputil

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"employee/internal/models"
	"employee/pkg/errors"
)

// StatusForCode maps service-layer error codes to HTTP statuses. Service code
// doesn't know about HTTP — it returns errors tagged with domain codes
// (NOT_FOUND, VALIDATION_ERROR, EMPLOYEE_EXISTS, …) and trusts the handler
// to map them. Unknown codes default to 500.
func StatusForCode(code string) int {
	switch code {
	case errors.CodeNotFound, errors.CodeEmployeeNotFound, errors.CodeJurisdictionNotFound:
		return http.StatusNotFound
	case errors.CodeValidation, errors.CodeInvalidRequest, errors.CodeInvalidInput, errors.CodeMissingHeader, errors.CodeBadRequest, errors.CodeInvalidUUID:
		return http.StatusBadRequest
	case errors.CodeUnauthorized:
		return http.StatusUnauthorized
	case errors.CodeForbidden:
		return http.StatusForbidden
	case errors.CodeEmployeeExists, errors.CodeJurisdictionExists, errors.CodeConflict,
		errors.CodeRowVersionMismatch,
		errors.CodeEmployeeAlreadyActive, errors.CodeEmployeeAlreadyInactive:
		return http.StatusConflict
	case errors.CodeDownstream, errors.CodeBadGateway:
		// A dependency we called (Keycloak, Individual service, Boundary
		// service, etc.) did not respond successfully. Not the client's
		// fault — they should retry with backoff, not fix their input.
		return http.StatusBadGateway
	}
	return http.StatusInternalServerError
}

// WriteError writes a single-error JSON array response and logs the failure.
// If err is an *errors.Error its Code/Message/Description populate the
// response; otherwise the bare error.Error() goes into Message. When the
// caller passes 500 as the default and the err carries a more specific code,
// the status is upgraded via StatusForCode.
func WriteError(c *gin.Context, logger *logrus.Logger, status int, err error) {
	if logger != nil {
		logger.WithError(err).Error("Request failed")
	}
	apiError := models.Error{
		Code:    errors.CodeInternal,
		Message: "An error occurred",
	}
	if e, ok := err.(*errors.Error); ok {
		apiError.Code = e.Code
		apiError.Message = e.Message
		if e.Description != "" {
			apiError.Description = e.Description
		}
		if status == http.StatusInternalServerError {
			status = StatusForCode(e.Code)
		}
	} else {
		apiError.Message = err.Error()
	}
	c.JSON(status, []models.Error{apiError})
}
