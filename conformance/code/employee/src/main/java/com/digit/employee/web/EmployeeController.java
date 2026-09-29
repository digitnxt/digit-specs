package com.digit.employee.web;

import com.digit.employee.constants.ErrorCodes;

import com.digit.employee.constants.Headers;
import com.digit.employee.constants.ValidationConstants;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.EmployeeResponse;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.model.OnboardRequest;
import com.digit.employee.model.OnboardResponse;
import com.digit.employee.model.PatchEmployeeRequest;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.service.EmployeeService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.digit.tracer.model.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Employee endpoints. Mirrors Go internal/handler/employee.go + routes. */
@RestController
@RequestMapping("/v3/employees")
public class EmployeeController {

    private final EmployeeService svc;
    private final ObjectMapper objectMapper;
    private final EmployeeProperties props;

    public EmployeeController(EmployeeService svc, ObjectMapper objectMapper, EmployeeProperties props) {
        this.svc = svc;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<List<EmployeeResponse>> createEmployees(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @RequestHeader(value = Headers.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) byte[] body) {
        // Go order: parse body, then check Authorization.
        List<CreateEmployeeRequest> req =
                ControllerSupport.parseBody(objectMapper, body, new TypeReference<List<CreateEmployeeRequest>>() {});
        // Authorization is only needed to call keycloak (userId validation). If keycloak is disabled,
        // no keycloak call is made, so the token is not required.
        if (props.getKeycloak().isEnabled() && (authHeader == null || authHeader.isEmpty())) {
            throw new CustomException(ErrorCodes.UNAUTHORIZED, "Authorization header is missing", HttpStatus.UNAUTHORIZED);
        }
        List<EmployeeResponse> result = svc.createEmployees(req, tenantId, authHeader, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Onboarding: create a Keycloak user + individual + employee in one call. A bearer token is always
     * required (onboarding provisions a Keycloak user); it is forwarded so Keycloak enforces the
     * caller's permissions. Mirrors Go handler.OnboardEmployee.
     */
    @PostMapping("/onboard")
    public ResponseEntity<OnboardResponse> onboardEmployee(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @RequestHeader(value = Headers.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) byte[] body) {
        OnboardRequest req = ControllerSupport.parseBody(objectMapper, body, OnboardRequest.class);
        if (authHeader == null || authHeader.isEmpty()) {
            throw new CustomException(ErrorCodes.UNAUTHORIZED, "Authorization header is missing", HttpStatus.UNAUTHORIZED);
        }
        OnboardResponse result = svc.onboardEmployee(req, tenantId, authHeader, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<EmployeeResponse>> searchEmployees(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "codes", required = false) List<String> codes,
            @RequestParam(value = "userIds", required = false) List<String> userIds,
            @RequestParam(value = "statuses", required = false) List<String> statuses,
            @RequestParam(value = "employeeTypes", required = false) List<String> employeeTypes,
            @RequestParam(value = "departments", required = false) List<String> departments,
            @RequestParam(value = "designations", required = false) List<String> designations,
            @RequestParam(value = "dateOfAppointmentFrom", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate dateOfAppointmentFrom,
            @RequestParam(value = "dateOfAppointmentTo", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate dateOfAppointmentTo,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "limit", required = false) String limitRaw,
            @RequestParam(value = "offset", required = false) String offsetRaw) {
        // Role-based search needs a bearer token to query Keycloak — Go requires Authorization only
        // when `role` is supplied, returning 401 otherwise. Not conditioned on keycloak.enabled:
        // that flag governs request-body id validation, and role resolution happens regardless of
        // it. Requiring the header here keeps an unauthenticated role search a clean 401 instead of
        // a 502 from Keycloak when no service account is configured to stand in.
        if (role != null && !role.isEmpty()
                && (authHeader == null || authHeader.isEmpty())) {
            throw new CustomException(ErrorCodes.UNAUTHORIZED, "Authorization header is required for role-based search", HttpStatus.UNAUTHORIZED);
        }
        // Parse as raw String (not int) so an over-max/non-numeric value is a clean 400, not a 500
        // from Spring's int conversion throwing before validation. Mirrors Go + the individual service.
        int limit = ControllerSupport.parsePagingParam("limit", limitRaw, 10);
        int offset = ControllerSupport.parsePagingParam("offset", offsetRaw, 0);
        ControllerSupport.validatePaging(limit, offset);
        if (ids != null) {
            for (String s : ids) {
                ControllerSupport.requireUUID(s, "Invalid id: " + s);
            }
        }
        // userIds hold Keycloak identifiers, which are not required to be UUIDs (create only checks
        // that the user exists), so only width and emptiness are enforced. A wholly empty `?userIds=`
        // never arrives — Spring drops it from the List — but a blank inside a comma list does, and
        // would silently become user_id IN ('').
        if (userIds != null) {
            for (String s : userIds) {
                if (s == null || s.isBlank()) {
                    throw new CustomException(ErrorCodes.VALIDATION_ERROR, "userIds must not contain blank values");
                }
                if (s.length() > ValidationConstants.USER_ID_MAX_LEN) {
                    throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                            "userIds entries must be at most " + ValidationConstants.USER_ID_MAX_LEN + " characters");
                }
            }
        }
        EmployeeSearchCriteria criteria = new EmployeeSearchCriteria();
        criteria.setIds(ids);
        criteria.setCodes(codes);
        criteria.setUserIds(userIds);
        criteria.setStatuses(statuses);
        criteria.setEmployeeTypes(employeeTypes);
        criteria.setDepartments(departments);
        criteria.setDesignations(designations);
        // LocalDate → ISO "yyyy-MM-dd" for the repo (a malformed date never reaches here — it fails
        // binding as a type mismatch → 400 via MalformedInputExceptionHandler).
        criteria.setDateOfAppointmentFrom(dateOfAppointmentFrom == null ? null : dateOfAppointmentFrom.toString());
        criteria.setDateOfAppointmentTo(dateOfAppointmentTo == null ? null : dateOfAppointmentTo.toString());
        criteria.setRole(role);
        criteria.setIsActive(isActive);
        criteria.setLimit(limit);
        criteria.setOffset(offset);
        criteria.setTenantId(tenantId);
        return ResponseEntity.ok(svc.searchEmployees(criteria, authHeader));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeResponse> getEmployeeByUUID(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @PathVariable("id") String id) {
        ControllerSupport.requireUUID(id, "Invalid employee UUID");
        return ResponseEntity.ok(svc.getEmployeeByUUID(id, tenantId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeResponse> updateEmployee(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) byte[] body) {
        // Go UpdateEmployee requires no Authorization — the strict PUT body carries only mutable
        // fields; immutable fields (code/userId/individualId/...) are absent and never revalidated.
        ControllerSupport.requireUUID(id, "Invalid employee UUID");
        UpdateEmployeeRequest req = ControllerSupport.parseBody(objectMapper, body, UpdateEmployeeRequest.class);
        return ResponseEntity.ok(svc.updateEmployee(id, req, tenantId, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> hardDeleteEmployee(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @PathVariable("id") String id) {
        ControllerSupport.requireUUID(id, "Invalid employee UUID");
        svc.hardDeleteEmployee(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EmployeeResponse> patchEmployee(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) byte[] body) {
        ControllerSupport.requireUUID(id, "Invalid employee UUID");
        PatchEmployeeRequest req = ControllerSupport.parseBody(objectMapper, body, PatchEmployeeRequest.class);
        return ResponseEntity.ok(svc.patchEmployee(id, req, tenantId, userId));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<EmployeeResponse> deactivateEmployee(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @PathVariable("id") String id) {
        // No request body — Go removed the DeactivationDetails DTO; status transition is implicit.
        ControllerSupport.requireUUID(id, "Invalid employee UUID");
        return ResponseEntity.ok(svc.deactivateEmployee(id, tenantId, userId));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<EmployeeResponse> reactivateEmployee(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @PathVariable("id") String id) {
        ControllerSupport.requireUUID(id, "Invalid employee UUID");
        return ResponseEntity.ok(svc.reactivateEmployee(id, tenantId, userId));
    }
}
