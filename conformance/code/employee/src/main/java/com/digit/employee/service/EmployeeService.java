package com.digit.employee.service;

import org.springframework.http.HttpStatus;

import com.digit.employee.constants.ErrorCodes;

import com.digit.employee.client.IdGenClient;
import com.digit.employee.client.IndividualClient;
import com.digit.employee.client.KeycloakClient;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.constants.ValidationConstants;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.CreateJurisdictionRequest;
import com.digit.employee.model.Employee;
import com.digit.employee.model.EmployeeResponse;
import com.digit.employee.model.EmployeePatch;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.PatchEmployeeRequest;
import com.digit.employee.model.JurisdictionResponse;
import com.digit.employee.model.JurisdictionSearchCriteria;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.pubsub.EventPublisher;
import com.digit.employee.repository.EmployeeRepository;
import org.digit.tracer.model.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Employee operations. Mirrors Go internal/service/employee_service.go.
 */
@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository repo;
    private final JurisdictionService jurisdictionSvc;
    private final IdGenClient idGenClient;
    private final IndividualClient individualClient;
    private final KeycloakClient keycloakClient;
    private final EmployeeProperties config;
    private final EventPublisher eventPublisher;
    private final BusinessMetrics businessMetrics;

    public EmployeeService(EmployeeRepository repo,
                           JurisdictionService jurisdictionSvc,
                           IdGenClient idGenClient,
                           IndividualClient individualClient,
                           KeycloakClient keycloakClient,
                           EmployeeProperties config,
                           EventPublisher eventPublisher,
                           BusinessMetrics businessMetrics) {
        this.repo = repo;
        this.jurisdictionSvc = jurisdictionSvc;
        this.idGenClient = idGenClient;
        this.individualClient = individualClient;
        this.keycloakClient = keycloakClient;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.businessMetrics = businessMetrics;
    }

    private String generateEmployeeCode(String tenantId) {
        // idgen is a downstream dependency — a failure/empty answer is not the client's fault, so
        // classify as DOWNSTREAM_ERROR (502, retryable), matching Go generateEmployeeCode.
        List<String> ids;
        try {
            ids = idGenClient.generateIDs(tenantId, 1, null);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "failed to generate employee code", HttpStatus.BAD_GATEWAY);
        }
        if (ids.isEmpty()) {
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "idgen returned no ID", HttpStatus.BAD_GATEWAY);
        }
        return ids.get(0);
    }

    /**
     * Validates individualId against the Individual service. Optional field: empty → no-op (staged
     * onboarding). Mirrors Go — a service fault is DOWNSTREAM_ERROR (502), an unknown id is
     * INVALID_REQUEST (400).
     */
    private void validateIndividualID(String tenantId, String individualID) {
        // Dependency flag: when the individual service is disabled we persist without validating.
        if (!config.getIndividual().isEnabled()) {
            return;
        }
        if (individualID == null || individualID.isEmpty()) {
            return;
        }
        String individual;
        try {
            individual = individualClient.getIndividualByID(tenantId, individualID);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "failed to validate individual ID", HttpStatus.BAD_GATEWAY);
        }
        if (individual == null) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "individual not found");
        }
    }

    /**
     * Validates userId against Keycloak. Optional field: empty → no-op. Mirrors Go — a service fault
     * is DOWNSTREAM_ERROR (502), an unknown id is INVALID_REQUEST (400).
     */
    private void validateUserID(String tenantId, String userID, String authHeader) {
        // Dependency flag: when keycloak is disabled we persist without validating the userId.
        if (!config.getKeycloak().isEnabled()) {
            return;
        }
        if (userID == null || userID.isEmpty()) {
            return;
        }
        String user;
        try {
            user = keycloakClient.getUserByID(tenantId, userID, authHeader);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "failed to validate user ID", HttpStatus.BAD_GATEWAY);
        }
        if (user == null) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "user not found in Keycloak");
        }
    }

    EmployeeResponse toEmployeeResponse(Employee emp, String tenantId) {
        if (emp == null) {
            return null;
        }
        List<JurisdictionResponse> jurisdictions = null;
        try {
            JurisdictionSearchCriteria criteria = new JurisdictionSearchCriteria();
            criteria.setTenantId(tenantId);
            jurisdictions = jurisdictionSvc.searchJurisdictions(emp.getId(), criteria);
        } catch (Exception e) {
            // Continue without jurisdictions if there's an error (mirrors Go).
        }

        EmployeeResponse r = new EmployeeResponse();
        r.setId(emp.getId());
        r.setCode(emp.getCode());
        r.setUserId(emp.getUserId());
        r.setIndividualId(emp.getIndividualId());
        r.setStatus(emp.getStatus());
        r.setEmployeeType(emp.getEmployeeType());
        r.setDateOfAppointment(emp.getDateOfAppointment());
        r.setDepartment(emp.getDepartment());
        r.setDesignation(emp.getDesignation());
        r.setActive(emp.isActive());
        r.setJurisdictions(jurisdictions);
        r.setAuditDetail(emp.getAuditDetails());
        return r;
    }

    @Transactional
    public List<EmployeeResponse> createEmployees(List<CreateEmployeeRequest> req, String tenantId,
                                                  String authHeader, String userId) {
        // Batch bounds mirror Go (OpenAPI minItems:1 / maxItems:100).
        if (req == null || req.isEmpty()) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "at least one employee record is required");
        }
        if (req.size() > ValidationConstants.MAX_CREATE_BATCH) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST,
                    "at most " + ValidationConstants.MAX_CREATE_BATCH + " employee records may be created per request");
        }

        List<EmployeeResponse> responses = new ArrayList<>(req.size());

        for (CreateEmployeeRequest r : req) {
            validateCreateRequest(r);
            validateUserID(tenantId, r.getUserId(), authHeader);
            validateIndividualID(tenantId, r.getIndividualId());
            validateDateOfAppointment(r.getDateOfAppointment());

            if (r.getCode() == null || r.getCode().isEmpty()) {
                r.setCode(generateEmployeeCode(tenantId));
            }

            long now = System.currentTimeMillis();
            Employee employee = new Employee();
            employee.setCode(r.getCode());
            employee.setUserId(r.getUserId());
            employee.setIndividualId(r.getIndividualId());
            employee.setStatus(r.getStatus());
            employee.setEmployeeType(r.getEmployeeType());
            employee.setDateOfAppointment(r.getDateOfAppointment());
            employee.setDepartment(r.getDepartment());
            employee.setDesignation(r.getDesignation());
            employee.setActive(true);
            employee.setTenantId(tenantId);
            employee.getAuditDetails().setCreatedBy(userId);
            employee.getAuditDetails().setModifiedBy(userId);
            employee.getAuditDetails().setCreatedTime(now);
            employee.getAuditDetails().setModifiedTime(now);
            if (r.getIsActive() != null) {
                employee.setActive(r.getIsActive());
            }

            repo.create(employee);

            if (r.getJurisdictions() != null && !r.getJurisdictions().isEmpty()) {
                for (Jurisdiction j : r.getJurisdictions()) {
                    CreateJurisdictionRequest jurisReq = new CreateJurisdictionRequest();
                    jurisReq.setBoundaryRelation(j.getBoundaryRelation());
                    jurisReq.setIsActive(j.isActive());
                    // Do NOT swallow: a jurisdiction failure must propagate so the surrounding
                    // @Transactional rolls back the employee insert too (mirrors Go — no silent
                    // partial success where an employee persists without its jurisdictions).
                    jurisdictionSvc.createJurisdiction(employee.getId(), jurisReq, tenantId, userId);
                }
            }

            Employee temp = repo.findByUUID(employee.getId(), tenantId);
            responses.add(toEmployeeResponse(temp, tenantId));
        }

        eventPublisher.publishEvent(config.getPubsub().getTopics().getCreateEmployee(), "CREATE",
                tenantId, "", responses, responses.size());

        businessMetrics.recordEmployeeCreated(tenantId, responses.size());
        return responses;
    }

    public List<EmployeeResponse> searchEmployees(EmployeeSearchCriteria criteria, String authHeader) {
        // Role search: resolve the Keycloak realm role to its member user IDs, then filter user_id IN.
        // Mirrors Go SearchEmployees — a role nobody holds short-circuits to an empty result before the
        // DB (an empty userIds would otherwise be skipped as "no filter" and return every employee).
        if (criteria.getRole() != null && !criteria.getRole().isEmpty()) {
            // The role filter's only purpose is to resolve members via keycloak; if keycloak is
            // disabled the feature is unavailable — fail loudly rather than return empty/over-broad.
            if (!config.getKeycloak().isEnabled()) {
                throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                        "role-based search requires keycloak to be enabled");
            }
            List<String> userIds;
            try {
                userIds = keycloakClient.getUserIDsByRole(criteria.getTenantId(), criteria.getRole(), authHeader);
            } catch (Exception e) {
                throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "keycloak role lookup failed", HttpStatus.BAD_GATEWAY);
            }
            if (userIds == null || userIds.isEmpty()) {
                businessMetrics.recordEmployeeSearched(criteria.getTenantId(), 0);
                return new ArrayList<>();
            }
            criteria.setUserIds(userIds);
        }

        List<Employee> employees = repo.search(criteria);
        List<EmployeeResponse> responses = new ArrayList<>(employees.size());
        for (Employee emp : employees) {
            responses.add(toEmployeeResponse(emp, criteria.getTenantId()));
        }
        businessMetrics.recordEmployeeSearched(criteria.getTenantId(), responses.size());
        return responses;
    }

    public EmployeeResponse getEmployeeByUUID(String uuid, String tenantId) {
        Employee employee = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND
        return toEmployeeResponse(employee, tenantId);
    }

    /**
     * PUT — strict full-state overwrite of the mutable surface. Mirrors Go UpdateEmployee: no auth
     * and no userId/individualId validation (those are immutable and absent from the body); immutable
     * fields are carried forward from the loaded row; jurisdictions are reconciled against the request
     * array; version is required and the write is CAS-guarded (409 on staleness).
     */
    @Transactional
    public EmployeeResponse updateEmployee(String uuid, UpdateEmployeeRequest req, String tenantId, String userId) {
        Employee existing = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND

        // Strict PUT: every mutable field is required + length-capped (mirrors Go binding).
        requireField("employeeType", req.getEmployeeType(), ValidationConstants.EMPLOYEE_TYPE_MAX_LEN);
        requireField("department", req.getDepartment(), ValidationConstants.DEPARTMENT_MAX_LEN);
        requireField("designation", req.getDesignation(), ValidationConstants.DESIGNATION_MAX_LEN);
        requireField("status", req.getStatus(), ValidationConstants.STATUS_MAX_LEN);
        if (req.getIsActive() == null) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "isActive is required");
        }
        if (req.getJurisdictions() == null) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "jurisdictions is required");
        }
        if (req.getVersion() == null) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "version is required");
        }
        // Optimistic fast-fail before touching jurisdictions; the CAS in repo.update closes the race.
        if (existing.getVersion() != req.getVersion()) {
            throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "employee was modified concurrently", HttpStatus.CONFLICT);
        }
        int expectedVersion = existing.getVersion();

        // Apply only mutable fields; immutable fields stay as loaded (repo.update also omits them).
        existing.setEmployeeType(req.getEmployeeType());
        existing.setDepartment(req.getDepartment());
        existing.setDesignation(req.getDesignation());
        existing.setStatus(req.getStatus());
        existing.setActive(req.getIsActive());
        existing.getAuditDetails().setModifiedBy(userId);
        existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());

        repo.update(existing, expectedVersion);
        existing.setVersion(expectedVersion + 1); // reflect the bump in the response
        // Reconcile: id+version → update in place, id-less → insert, omitted → deactivate.
        jurisdictionSvc.reconcileJurisdictions(uuid, req.getJurisdictions(), tenantId, userId);

        EmployeeResponse resp = toEmployeeResponse(existing, tenantId);
        eventPublisher.publishEvent(config.getPubsub().getTopics().getUpdateEmployee(), "UPDATE",
                tenantId, "", resp, 1);
        businessMetrics.recordEmployeeUpdated(tenantId, 1);
        return resp;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * In-process bind validation for create, mirroring Go's binding tags so bad input returns a clean
     * 400 instead of overflowing a column into a Postgres 22001 → 500. Required: employeeType,
     * department, designation (≤128). Optional with caps: code/userId/individualId/status (≤64).
     */
    private static void validateCreateRequest(CreateEmployeeRequest r) {
        requireField("employeeType", r.getEmployeeType(), ValidationConstants.EMPLOYEE_TYPE_MAX_LEN);
        requireField("department", r.getDepartment(), ValidationConstants.DEPARTMENT_MAX_LEN);
        requireField("designation", r.getDesignation(), ValidationConstants.DESIGNATION_MAX_LEN);
        maxField("code", r.getCode(), ValidationConstants.CODE_MAX_LEN);
        maxField("userId", r.getUserId(), ValidationConstants.USER_ID_MAX_LEN);
        maxField("individualId", r.getIndividualId(), ValidationConstants.INDIVIDUAL_ID_MAX_LEN);
        maxField("status", r.getStatus(), ValidationConstants.STATUS_MAX_LEN);
    }

    private static void requireField(String name, String v, int max) {
        if (v == null || v.isEmpty()) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, name + " is required");
        }
        if (v.length() > max) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, name + " must not exceed " + max + " characters");
        }
    }

    private static void maxField(String name, String v, int max) {
        if (v != null && v.length() > max) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, name + " must not exceed " + max + " characters");
        }
    }

    /**
     * Optional appointment-date range check (mirrors Go validateDateOfAppointment): a supplied value
     * must not be in the future and must not predate 1900. VALIDATION_ERROR (400) on violation.
     */
    private static void validateDateOfAppointment(java.time.OffsetDateTime d) {
        if (d == null) {
            return;
        }
        if (d.isAfter(java.time.OffsetDateTime.now())) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "dateOfAppointment cannot be in the future");
        }
        if (d.toLocalDate().isBefore(java.time.LocalDate.of(ValidationConstants.MIN_APPOINTMENT_YEAR, 1, 1))) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "dateOfAppointment is too far in the past");
        }
    }

    @Transactional
    public void hardDeleteEmployee(String uuid, String tenantId) {
        // Best-effort detach jurisdictions first (mirrors Go: errors logged, not fatal).
        try {
            JurisdictionSearchCriteria criteria = new JurisdictionSearchCriteria();
            criteria.setTenantId(tenantId);
            List<JurisdictionResponse> jurs = jurisdictionSvc.searchJurisdictions(uuid, criteria);
            for (JurisdictionResponse jur : jurs) {
                try {
                    jurisdictionSvc.deleteJurisdiction(jur.getId(), tenantId);
                } catch (Exception e) {
                    log.error("Failed to delete jurisdiction jurisdictionId={}", jur.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch jurisdictions for deletion employeeId={}", uuid, e);
        }

        repo.delete(uuid, tenantId); // throws NOT_FOUND

        Map<String, String> data = new HashMap<>();
        data.put("id", uuid);
        eventPublisher.publishEvent(config.getPubsub().getTopics().getDeleteEmployee(), "DELETE",
                tenantId, "", data, 1);

        businessMetrics.recordEmployeeDeleted(tenantId);
    }

    /**
     * PATCH — partial update. Mirrors Go PatchEmployee: empty body → 400; version required and CAS-
     * guarded; only supplied fields are written (via repo.patch); jurisdictions reconciled when
     * supplied (null → left untouched).
     */
    @Transactional
    public EmployeeResponse patchEmployee(String uuid, PatchEmployeeRequest req, String tenantId, String userId) {
        if (!req.hasAnyField()) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                    "at least one mutable field must be supplied for patch");
        }
        if (req.getVersion() == null) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "version is required");
        }
        // Length caps on supplied fields (mirrors Go binding) → clean 400 instead of a DB 22001 500.
        maxField("status", req.getStatus(), ValidationConstants.STATUS_MAX_LEN);
        maxField("employeeType", req.getEmployeeType(), ValidationConstants.EMPLOYEE_TYPE_MAX_LEN);
        maxField("department", req.getDepartment(), ValidationConstants.DEPARTMENT_MAX_LEN);
        maxField("designation", req.getDesignation(), ValidationConstants.DESIGNATION_MAX_LEN);

        // Load for existence (clean 404) and the optimistic fast-fail; the CAS in repo.patch is the
        // authoritative guard.
        Employee existing = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND
        if (existing.getVersion() != req.getVersion()) {
            throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "employee was modified concurrently", HttpStatus.CONFLICT);
        }
        int expectedVersion = existing.getVersion();

        EmployeePatch patch = new EmployeePatch();
        patch.setStatus(req.getStatus());
        patch.setEmployeeType(req.getEmployeeType());
        patch.setDepartment(req.getDepartment());
        patch.setDesignation(req.getDesignation());
        patch.setIsActive(req.getIsActive());
        patch.setVersion(expectedVersion + 1);
        patch.setModifiedBy(userId);
        patch.setModifiedTime(System.currentTimeMillis());

        repo.patch(uuid, tenantId, patch, expectedVersion);

        if (req.getJurisdictions() != null) {
            jurisdictionSvc.reconcileJurisdictions(uuid, req.getJurisdictions(), tenantId, userId);
        }

        EmployeeResponse resp = getEmployeeByUUID(uuid, tenantId);
        eventPublisher.publishEvent(config.getPubsub().getTopics().getUpdateEmployee(), "UPDATE",
                tenantId, "", resp, 1);
        businessMetrics.recordEmployeeUpdated(tenantId, 1);
        return resp;
    }

    /**
     * Deactivate — enforces the active→inactive transition. Mirrors Go DeactivateEmployee: 404 when
     * absent, 409 EMPLOYEE_ALREADY_INACTIVE on a redundant transition, stamps modifiedBy/modifiedTime.
     * Takes no request body (Go removed the DeactivationDetails DTO).
     */
    @Transactional
    public EmployeeResponse deactivateEmployee(String uuid, String tenantId, String userId) {
        Employee existing = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND
        if (!existing.isActive()) {
            throw new CustomException(ErrorCodes.EMPLOYEE_ALREADY_INACTIVE, "employee is already inactive", HttpStatus.CONFLICT);
        }
        int expectedVersion = existing.getVersion();
        existing.setActive(false);
        existing.getAuditDetails().setModifiedBy(userId);
        existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());
        repo.update(existing, expectedVersion);
        businessMetrics.recordEmployeeDeactivated(tenantId);
        return getEmployeeByUUID(uuid, tenantId);
    }

    /**
     * Reactivate — enforces the inactive→active transition. 409 EMPLOYEE_ALREADY_ACTIVE when already
     * active. Mirrors Go ReactivateEmployee.
     */
    @Transactional
    public EmployeeResponse reactivateEmployee(String uuid, String tenantId, String userId) {
        Employee existing = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND
        if (existing.isActive()) {
            throw new CustomException(ErrorCodes.EMPLOYEE_ALREADY_ACTIVE, "employee is already active", HttpStatus.CONFLICT);
        }
        int expectedVersion = existing.getVersion();
        existing.setActive(true);
        existing.getAuditDetails().setModifiedBy(userId);
        existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());
        repo.update(existing, expectedVersion);
        businessMetrics.recordEmployeeReactivated(tenantId);
        return getEmployeeByUUID(uuid, tenantId);
    }
}
