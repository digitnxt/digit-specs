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

const tracerName = "employee-handler"

type EmployeeHandler struct {
	service         service.EmployeeService
	logger          *logrus.Logger
	keycloakEnabled bool
}

func NewEmployeeHandler(employeeService service.EmployeeService, logger *logrus.Logger, keycloakEnabled bool) *EmployeeHandler {
	return &EmployeeHandler{
		service:         employeeService,
		logger:          logger,
		keycloakEnabled: keycloakEnabled,
	}
}

func (h *EmployeeHandler) CreateEmployees(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.create")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	var req []*models.CreateEmployeeRequest
	if !httputil.BindBody(c, span, h.logger, &req) {
		return
	}

	// Authorization is only needed to call keycloak (userId validation). If keycloak is disabled,
	// no keycloak call is made, so the token is not required.
	var authHeader string
	if h.keycloakEnabled {
		var ok bool
		if authHeader, ok = httputil.RequireAuthHeader(c, span, h.logger); !ok {
			return
		}
	} else {
		authHeader = c.GetHeader("Authorization")
	}

	span.SetAttributes(attribute.Int("employee.count", len(req)))

	employees, err := h.service.CreateEmployees(ctx, req, authHeader)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to create employees")
		return
	}

	span.SetStatus(codes.Ok, "Employees created")
	c.JSON(http.StatusCreated, employees)
}

func (h *EmployeeHandler) SearchEmployees(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.search")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	criteria := &models.EmployeeSearchCriteria{}
	if err := c.ShouldBindQuery(criteria); err != nil {
		// Wrap the raw Gin/Go parse error as a typed INVALID_REQUEST so
		// StatusForCode maps it to 400 inside FailService (which otherwise
		// defaults to 500 for untyped errors).
		wrapped := errors.New(errors.CodeInvalidRequest, err.Error())
		httputil.FailService(c, span, h.logger, wrapped, "Invalid query parameters")
		return
	}

	// Auth header is only needed when searching by role — that path calls the
	// Keycloak admin API to resolve the role's member user IDs. Role-less
	// searches don't touch Keycloak, so we don't force a 401 on them.
	var authHeader string
	if criteria.Role != "" && h.keycloakEnabled {
		var ok bool
		if authHeader, ok = httputil.RequireAuthHeader(c, span, h.logger); !ok {
			return
		}
	}

	employees, err := h.service.SearchEmployees(ctx, criteria, authHeader)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to search employees")
		return
	}

	span.SetStatus(codes.Ok, "Employees searched")
	c.JSON(http.StatusOK, employees)
}

func (h *EmployeeHandler) GetEmployeeByUUID(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.get_by_uuid")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	id, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}
	span.SetAttributes(attribute.String("employee.uuid", id))

	employee, err := h.service.GetEmployeeByUUID(ctx, id)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to get employee")
		return
	}

	span.SetStatus(codes.Ok, "Employee retrieved")
	c.JSON(http.StatusOK, employee)
}

func (h *EmployeeHandler) UpdateEmployee(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.update")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	id, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}

	var req models.UpdateEmployeeRequest
	if !httputil.BindBody(c, span, h.logger, &req) {
		return
	}

	span.SetAttributes(attribute.String("employee.uuid", id))

	employee, err := h.service.UpdateEmployee(ctx, id, &req)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to update employee")
		return
	}

	span.SetStatus(codes.Ok, "Employee updated")
	c.JSON(http.StatusOK, employee)
}

func (h *EmployeeHandler) HardDeleteEmployee(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.delete")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	id, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}
	span.SetAttributes(attribute.String("employee.uuid", id))

	if err := h.service.HardDeleteEmployee(ctx, id); err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to delete employee")
		return
	}

	span.SetStatus(codes.Ok, "Employee deleted")
	c.Status(http.StatusNoContent)
}

func (h *EmployeeHandler) PatchEmployee(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.patch")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	id, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}

	var req models.PatchEmployeeRequest
	if !httputil.BindBody(c, span, h.logger, &req) {
		return
	}
	span.SetAttributes(attribute.String("employee.uuid", id))

	employee, err := h.service.PatchEmployee(ctx, id, &req)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to patch employee")
		return
	}

	span.SetStatus(codes.Ok, "Employee patched")
	c.JSON(http.StatusOK, employee)
}

func (h *EmployeeHandler) DeactivateEmployee(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.deactivate")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	id, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}
	span.SetAttributes(attribute.String("employee.uuid", id))

	employee, err := h.service.DeactivateEmployee(ctx, id)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to deactivate employee")
		return
	}

	span.SetStatus(codes.Ok, "Employee deactivated")
	c.JSON(http.StatusOK, employee)
}

func (h *EmployeeHandler) ReactivateEmployee(c *gin.Context) {
	tracer := otel.Tracer(tracerName)
	ctx, span := tracer.Start(c.Request.Context(), "handler.employee.reactivate")
	defer span.End()

	if _, ok := httputil.ResolveTenantID(c, span, h.logger); !ok {
		return
	}

	id, ok := httputil.RequireUUIDParam(c, span, h.logger, "id", "employee")
	if !ok {
		return
	}
	span.SetAttributes(attribute.String("employee.uuid", id))

	employee, err := h.service.ReactivateEmployee(ctx, id)
	if err != nil {
		httputil.FailService(c, span, h.logger, err, "Failed to reactivate employee")
		return
	}

	span.SetStatus(codes.Ok, "Employee reactivated")
	c.JSON(http.StatusOK, employee)
}
