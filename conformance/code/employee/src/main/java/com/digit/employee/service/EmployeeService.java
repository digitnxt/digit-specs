package com.digit.employee.service;

import org.springframework.http.HttpStatus;

import com.digit.employee.constants.ErrorCodes;

import com.digit.employee.client.IdGenApiException;
import com.digit.employee.client.IdGenClient;
import com.digit.employee.client.IndividualApiException;
import com.digit.employee.client.IndividualClient;
import com.digit.employee.client.KeycloakApiException;
import com.digit.employee.client.KeycloakClient;
import com.digit.employee.client.KeycloakUserRequest;
import com.digit.employee.config.EmployeeProperties;
import com.digit.employee.constants.ValidationConstants;
import com.digit.employee.model.CreateEmployeeRequest;
import com.digit.employee.model.CreateJurisdictionRequest;
import com.digit.employee.model.Employee;
import com.digit.employee.model.EmployeeResponse;
import com.digit.employee.model.EmployeePatch;
import com.digit.employee.model.EmployeeSearchCriteria;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.OnboardRequest;
import com.digit.employee.model.OnboardResponse;
import com.digit.employee.model.OnboardUser;
import com.digit.employee.model.OnboardUserResponse;
import com.digit.employee.model.PatchEmployeeRequest;
import com.digit.employee.model.JurisdictionResponse;
import com.digit.employee.model.UpdateEmployeeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.digit.employee.observability.BusinessMetrics;
import com.digit.employee.pubsub.EventPublisher;
import com.digit.employee.repository.EmployeeRepository;
import org.digit.tracer.model.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

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
    /**
     * Writes run in an explicit transaction around their SQL only, never around the downstream calls
     * that validate them: a transaction holds a pooled connection from start to commit. Explicit
     * rather than {@code @Transactional} because onboard reaches create through a self-call, which
     * the proxy never sees.
     */
    private final TransactionOperations tx;

    public EmployeeService(EmployeeRepository repo,
                           JurisdictionService jurisdictionSvc,
                           IdGenClient idGenClient,
                           IndividualClient individualClient,
                           KeycloakClient keycloakClient,
                           EmployeeProperties config,
                           EventPublisher eventPublisher,
                           BusinessMetrics businessMetrics,
                           TransactionOperations tx) {
        this.repo = repo;
        this.jurisdictionSvc = jurisdictionSvc;
        this.idGenClient = idGenClient;
        this.individualClient = individualClient;
        this.keycloakClient = keycloakClient;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.businessMetrics = businessMetrics;
        this.tx = tx;
    }

    private String generateEmployeeCode(String tenantId) {
        // idgen is a downstream dependency — a failure/empty answer is not the client's fault, so
        // classify as DOWNSTREAM_ERROR (502, retryable), matching Go generateEmployeeCode. A missing
        // template is the exception: retrying cannot succeed until the tenant's template is created.
        List<String> ids;
        try {
            ids = idGenClient.generateIDs(tenantId, 1, null);
        } catch (Exception e) {
            // Log the cause before flattening to a client-safe error: IdGenClient builds the only
            // message that says *why* (status + response body, or the transport failure), and the
            // CustomException message is echoed to the caller, so it must not carry it. Without
            // this the failure is undiagnosable.
            log.error("idgen employee-code generation failed tenantId={}", tenantId, e);
            if (e instanceof IdGenApiException idgen && idgen.getStatusCode() == 404) {
                throw new CustomException(ErrorCodes.IDGEN_TEMPLATE_NOT_FOUND,
                        "failed to generate employee code: idgen template '" + config.getIdgen().getIdgenName()
                                + "' not found for tenant " + tenantId,
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
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
        } catch (IndividualApiException e) {
            // A 400 rejects the id the caller sent (e.g. not a UUID); anything else is the dependency's.
            if (e.getStatusCode() == 400) {
                log.warn("individual rejected individualId tenantId={}", tenantId, e);
                String reason = e.errorMessage();
                throw new CustomException(ErrorCodes.INVALID_REQUEST,
                        reason == null ? "invalid individualId" : "invalid individualId: " + reason);
            }
            log.error("failed to validate individual ID tenantId={}", tenantId, e);
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "failed to validate individual ID", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("failed to validate individual ID tenantId={}", tenantId, e);
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
            log.error("failed to validate user ID tenantId={}", tenantId, e);
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "failed to validate user ID", HttpStatus.BAD_GATEWAY);
        }
        if (user == null) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "user not found in Keycloak");
        }
    }

    EmployeeResponse toEmployeeResponse(Employee emp, String tenantId) {
        return toEmployeeResponses(List.of(emp), tenantId).get(0);
    }

    /**
     * Maps employees to responses, loading every jurisdiction of every employee in one query. A
     * failed load propagates: answering with no jurisdictions would be indistinguishable from an
     * employee that has none.
     */
    List<EmployeeResponse> toEmployeeResponses(List<Employee> employees, String tenantId) {
        if (employees.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>(employees.size());
        for (Employee emp : employees) {
            ids.add(emp.getId());
        }
        Map<String, List<JurisdictionResponse>> jurisdictions = jurisdictionSvc.jurisdictionsByEmployee(tenantId, ids);
        List<EmployeeResponse> responses = new ArrayList<>(employees.size());
        for (Employee emp : employees) {
            responses.add(buildResponse(emp, jurisdictions.getOrDefault(emp.getId(), new ArrayList<>())));
        }
        return responses;
    }

    private static EmployeeResponse buildResponse(Employee emp, List<JurisdictionResponse> jurisdictions) {
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
        r.setIsActive(emp.getIsActive());
        r.setVersion(emp.getVersion());
        r.setJurisdictions(jurisdictions);
        r.setAuditDetail(emp.getAuditDetails());
        return r;
    }

    /** A create-batch item that passed every check, including the downstream ones, but is not yet written. */
    private record PendingEmployee(Employee employee, List<CreateJurisdictionRequest> jurisdictions) {}

    /**
     * Creates a batch all-or-nothing. Every downstream call (Keycloak, individual, idgen, boundary)
     * runs first, with no connection held; only then do the inserts run, in one short transaction.
     * Events go out after the commit, so a rolled-back batch publishes nothing.
     */
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

        List<PendingEmployee> pending = new ArrayList<>(req.size());

        for (CreateEmployeeRequest r : req) {
            // A null element in the batch array (POST /employees [null]) → 400, not an NPE → 500.
            if (r == null) {
                throw new CustomException(ErrorCodes.VALIDATION_ERROR, "employee entries must not be null");
            }
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
            employee.setIsActive(true);
            employee.setTenantId(tenantId);
            employee.getAuditDetails().setCreatedBy(userId);
            employee.getAuditDetails().setModifiedBy(userId);
            employee.getAuditDetails().setCreatedTime(now);
            employee.getAuditDetails().setModifiedTime(now);
            if (r.getIsActive() != null) {
                employee.setIsActive(r.getIsActive());
            }

            List<CreateJurisdictionRequest> jurisdictions = new ArrayList<>();
            if (r.getJurisdictions() != null) {
                for (Jurisdiction j : r.getJurisdictions()) {
                    // A null array element (jurisdictions: [null]) → 400, not an NPE → 500.
                    if (j == null) {
                        throw new CustomException(ErrorCodes.VALIDATION_ERROR, "jurisdictions entries must not be null");
                    }
                    jurisdictionSvc.validateRelations(tenantId, j.getBoundaryRelation());
                    CreateJurisdictionRequest jurisReq = new CreateJurisdictionRequest();
                    jurisReq.setBoundaryRelation(j.getBoundaryRelation());
                    jurisReq.setIsActive(j.getIsActive());
                    jurisdictions.add(jurisReq);
                }
            }
            pending.add(new PendingEmployee(employee, jurisdictions));
        }

        // One transaction for the whole batch: any failure rolls back every insert, so no employee
        // persists without its jurisdictions (mirrors Go — no silent partial success).
        List<JurisdictionResponse> createdJurisdictions = new ArrayList<>();
        List<Employee> created = tx.execute(status -> {
            List<Employee> rows = new ArrayList<>(pending.size());
            for (PendingEmployee p : pending) {
                Employee stored = repo.create(p.employee());
                for (CreateJurisdictionRequest jurisReq : p.jurisdictions()) {
                    createdJurisdictions.add(
                            jurisdictionSvc.insertJurisdiction(stored.getId(), jurisReq, tenantId, userId));
                }
                rows.add(stored);
            }
            return rows;
        });
        List<EmployeeResponse> responses = toEmployeeResponses(created, tenantId);

        for (JurisdictionResponse j : createdJurisdictions) {
            jurisdictionSvc.publishCreated(tenantId, j);
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
        //
        // When the client also supplied userIds the two are intersected: role + userIds means "these
        // users, but only the ones holding the role", consistent with every other filter pair ANDing.
        // Intersecting before the query (rather than filtering results afterwards) is what keeps
        // limit/offset correct — Postgres pages over the fully-filtered set.
        if (criteria.getRole() != null && !criteria.getRole().isEmpty()) {
            // Deliberately NOT gated on keycloak.enabled: that flag controls whether an id supplied
            // in a request body is validated against its owning service, not whether the service is
            // reachable. Resolving role members is what this filter *is* — the data lives only in
            // Keycloak — so the call is unconditional and a failure surfaces as DOWNSTREAM_ERROR
            // rather than being silently skipped or rejected as invalid input.
            List<String> userIds;
            try {
                userIds = keycloakClient.getUserIDsByRole(criteria.getTenantId(), criteria.getRole(), authHeader);
            } catch (Exception e) {
                log.error("keycloak role lookup failed tenantId={}", criteria.getTenantId(), e);
                throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "keycloak role lookup failed", HttpStatus.BAD_GATEWAY);
            }
            if (userIds == null || userIds.isEmpty()) {
                businessMetrics.recordEmployeeSearched(criteria.getTenantId(), 0);
                return new ArrayList<>();
            }
            List<String> requested = criteria.getUserIds();
            if (requested != null && !requested.isEmpty()) {
                java.util.Set<String> members = new java.util.HashSet<>(userIds);
                userIds = requested.stream().filter(members::contains).toList();
                // None of the requested users holds the role — an empty IN list would be dropped as
                // "no filter", so short-circuit rather than returning every employee.
                if (userIds.isEmpty()) {
                    businessMetrics.recordEmployeeSearched(criteria.getTenantId(), 0);
                    return new ArrayList<>();
                }
            }
            criteria.setUserIds(userIds);
        }

        List<EmployeeResponse> responses = toEmployeeResponses(repo.search(criteria), criteria.getTenantId());
        businessMetrics.recordEmployeeSearched(criteria.getTenantId(), responses.size());
        return responses;
    }

    public EmployeeResponse getEmployeeByUUID(String uuid, String tenantId) {
        Employee employee = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND
        return toEmployeeResponse(employee, tenantId);
    }

    /**
     * Onboarding: provisions a Keycloak user, an individual, and an employee in one call. Mirrors Go
     * {@code OnboardEmployee}. Order is user → individual → employee (dependency order: both downstream
     * records need the new userId, and the employee needs the individual's UUID). Roles are validated
     * to exist before any write, so a bad role name fails with no orphan. On a downstream failure,
     * compensations run in reverse order (individual soft-deleted, then Keycloak user deleted).
     *
     * Deliberately not {@code @Transactional}: that would hold a pooled connection across every
     * Keycloak and individual call below. The employee+jurisdiction insert gets its all-or-nothing
     * behaviour from createEmployees' own short transaction; the external Keycloak/individual calls
     * are not transactional either way and are undone by the explicit compensations.
     */
    public OnboardResponse onboardEmployee(OnboardRequest req, String tenantId, String authHeader, String userId) {
        // --- validate the user slice (Keycloak has no domain validator; the service owns it) ---
        OnboardUser user = req.getUser();
        if (user == null) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "user is required");
        }
        if (user.getMobileNumber() == null || user.getMobileNumber().isEmpty()) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "user.mobileNumber is required");
        }
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "user.password is required");
        }
        if (req.getEmployee() == null) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "employee is required");
        }
        JsonNode individual = req.getIndividual();
        if (individual == null || !individual.isObject()) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "individual is required and must be a JSON object");
        }

        // --- validate that every requested role exists BEFORE creating anything (fail fast, no orphan).
        // Escalation is guarded by Keycloak itself: the caller's token is forwarded, so a caller can
        // only assign roles it is permitted to grant. ---
        List<JsonNode> roleReps = new ArrayList<>();
        if (user.getRoles() != null) {
            for (String name : user.getRoles()) {
                if (name == null || name.isEmpty()) {
                    continue;
                }
                JsonNode role;
                try {
                    role = keycloakClient.getRealmRole(tenantId, name, authHeader);
                } catch (Exception e) {
                    log.error("failed to look up realm role tenantId={}", tenantId, e);
                    throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "failed to look up realm role", HttpStatus.BAD_GATEWAY);
                }
                if (role == null) {
                    throw new CustomException(ErrorCodes.INVALID_REQUEST, "role '" + name + "' does not exist in realm");
                }
                roleReps.add(role);
            }
        }

        // --- step 1: create the Keycloak user (username = mobile, password inline, no forced reset) ---
        String kcUserID;
        try {
            KeycloakUserRequest kcReq = new KeycloakUserRequest(
                    user.getMobileNumber(), user.getEmail(), user.getFirstName(), user.getLastName(),
                    user.getPassword(), user.isEmailVerified());
            kcUserID = keycloakClient.createUser(tenantId, kcReq, authHeader);
        } catch (KeycloakApiException e) {
            if (e.getStatusCode() == 409) {
                throw new CustomException(ErrorCodes.CONFLICT, "a user with this mobile number or email already exists", HttpStatus.CONFLICT);
            }
            throw mapCallerKeycloakError(e, tenantId, "failed to create user in keycloak", "not permitted to create users in this tenant");
        } catch (Exception e) {
            throw mapCallerKeycloakError(e, tenantId, "failed to create user in keycloak", "not permitted to create users in this tenant");
        }

        // --- step 2: assign roles; compensate (delete user) on failure ---
        if (!roleReps.isEmpty()) {
            try {
                keycloakClient.assignRealmRoles(tenantId, kcUserID, roleReps, authHeader);
            } catch (Exception e) {
                compensateUser(tenantId, kcUserID, authHeader);
                throw mapCallerKeycloakError(e, tenantId, "failed to assign roles to user", "not permitted to assign one of the requested roles");
            }
        }

        // --- step 3: create the individual (inject the new userId; forward caller as audit actor) ---
        Map<String, Object> indResp;
        try {
            indResp = individualClient.createIndividual(tenantId, userId, kcUserID, individual);
        } catch (Exception e) {
            compensateUser(tenantId, kcUserID, authHeader);
            throw mapIndividualError(e, tenantId, "failed to create individual");
        }
        Object idVal = indResp.get("id");
        String individualUuid = idVal == null ? "" : idVal.toString();
        if (individualUuid.isEmpty()) {
            compensateUser(tenantId, kcUserID, authHeader);
            throw new CustomException(ErrorCodes.DOWNSTREAM_ERROR, "individual service returned an empty id", HttpStatus.BAD_GATEWAY);
        }

        // --- step 4: create the employee (inject userId + individual UUID) ---
        CreateEmployeeRequest emp = req.getEmployee();
        emp.setUserId(kcUserID);
        emp.setIndividualId(individualUuid); // the UUID, not the human individualId code
        List<EmployeeResponse> created;
        try {
            created = createEmployees(List.of(emp), tenantId, authHeader, userId);
        } catch (RuntimeException e) {
            // Reverse order: soft-delete the individual, then delete the Keycloak user. Any employee
            // insert was already rolled back by createEmployees' own transaction.
            compensateIndividual(tenantId, userId, individualUuid);
            compensateUser(tenantId, kcUserID, authHeader);
            throw e;
        }

        // --- assemble the minimal response (server-decided facts only) ---
        List<String> roleNames = new ArrayList<>(roleReps.size());
        for (JsonNode r : roleReps) {
            JsonNode n = r.get("name");
            if (n != null) {
                roleNames.add(n.asText());
            }
        }
        OnboardUserResponse userResp = new OnboardUserResponse();
        userResp.setId(kcUserID);
        userResp.setUsername(user.getMobileNumber()); // username = mobileNumber (our derivation rule)
        userResp.setRoles(roleNames);

        OnboardResponse resp = new OnboardResponse();
        resp.setUser(userResp);
        resp.setIndividual(indResp);
        resp.setEmployee(created.get(0));
        return resp;
    }

    /**
     * Maps an individual-service create failure: a 409 is a conflict, any other 4xx is a bad request
     * (the client's individual payload), and everything else (5xx, transport) is a downstream failure.
     * Mirrors Go {@code mapIndividualErrCode}.
     */
    /**
     * Maps a failed Keycloak call made with the caller's token. Keycloak's 400/401/403/409 are about
     * the caller (their input or their permissions) and keep their status; anything else is a
     * downstream failure (502). The Keycloak status and body are logged, since the client message
     * carries at most Keycloak's errorMessage.
     */
    private CustomException mapCallerKeycloakError(Exception e, String tenantId, String failedMessage, String forbiddenMessage) {
        if (!(e instanceof KeycloakApiException kc)) {
            log.error("{} tenantId={}", failedMessage, tenantId, e);
            return new CustomException(ErrorCodes.DOWNSTREAM_ERROR, failedMessage, HttpStatus.BAD_GATEWAY);
        }
        String reason = kc.errorMessage();
        String detailed = reason == null ? failedMessage : failedMessage + ": " + reason;
        CustomException mapped = switch (kc.getStatusCode()) {
            case 400 -> new CustomException(ErrorCodes.INVALID_REQUEST, detailed, HttpStatus.BAD_REQUEST);
            case 401 -> new CustomException(ErrorCodes.UNAUTHORIZED, "caller token rejected by keycloak", HttpStatus.UNAUTHORIZED);
            case 403 -> new CustomException(ErrorCodes.FORBIDDEN, forbiddenMessage, HttpStatus.FORBIDDEN);
            case 409 -> new CustomException(ErrorCodes.CONFLICT, detailed, HttpStatus.CONFLICT);
            default -> null;
        };
        if (mapped != null) {
            log.warn("{} tenantId={}", failedMessage, tenantId, e);
            return mapped;
        }
        log.error("{} tenantId={}", failedMessage, tenantId, e);
        return new CustomException(ErrorCodes.DOWNSTREAM_ERROR, failedMessage, HttpStatus.BAD_GATEWAY);
    }

    /**
     * Maps a failed individual-service call. Its 400/422/409 reject the individual payload the caller
     * sent, so they keep their meaning and carry the service's reason. Anything else — including
     * 401/403, which concern employee's own call (the caller's token is not forwarded) — is a
     * downstream failure (502).
     */
    private CustomException mapIndividualError(Exception e, String tenantId, String message) {
        if (e instanceof IndividualApiException ind) {
            int sc = ind.getStatusCode();
            if (sc == 400 || sc == 422 || sc == 409) {
                log.warn("{} tenantId={}", message, tenantId, e);
                String reason = ind.errorMessage();
                String detailed = reason == null ? message : message + ": " + reason;
                return sc == 409
                        ? new CustomException(ErrorCodes.CONFLICT, detailed, HttpStatus.CONFLICT)
                        : new CustomException(ErrorCodes.INVALID_REQUEST, detailed);
            }
        }
        log.error("{} tenantId={}", message, tenantId, e);
        return new CustomException(ErrorCodes.DOWNSTREAM_ERROR, message, HttpStatus.BAD_GATEWAY);
    }

    /**
     * Best-effort deletes a Keycloak user created earlier in the saga. A failure here is the "manual
     * cleanup required" case: logged loudly with the ids but never masks the original error.
     */
    private void compensateUser(String tenantId, String userID, String authHeader) {
        try {
            keycloakClient.deleteUser(tenantId, userID, authHeader);
        } catch (Exception e) {
            log.error("onboarding compensation failed: could not delete keycloak user (manual cleanup required) tenantId={} keycloakUserId={}",
                    tenantId, userID, e);
        }
    }

    /** Best-effort soft-deletes an individual created earlier in the saga. Same semantics as {@link #compensateUser}. */
    private void compensateIndividual(String tenantId, String auditUserId, String individualId) {
        try {
            individualClient.deleteIndividual(tenantId, auditUserId, individualId);
        } catch (Exception e) {
            log.error("onboarding compensation failed: could not delete individual (manual cleanup required) tenantId={} individualId={}",
                    tenantId, individualId, e);
        }
    }

    /**
     * PUT — strict full-state overwrite of the mutable surface. Mirrors Go UpdateEmployee: no auth
     * and no userId/individualId validation (those are immutable and absent from the body); immutable
     * fields are carried forward from the loaded row; jurisdictions are reconciled against the request
     * array; version is required and the write is CAS-guarded (409 on staleness). The jurisdiction
     * array is fully validated, boundary lookups included, before the write transaction opens.
     */
    public EmployeeResponse updateEmployee(String uuid, UpdateEmployeeRequest req, String tenantId, String userId) {
        // Body validation first — mirrors Go, where bind-time validation returns 400 before the
        // row is loaded (so PUT to a missing id with an invalid body is 400, not 404).
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

        Employee existing = repo.findByUUID(uuid, tenantId); // throws NOT_FOUND

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
        existing.setIsActive(req.getIsActive());
        existing.getAuditDetails().setModifiedBy(userId);
        existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());

        // Reconcile: id+version → update in place, id-less → insert, omitted → deactivate.
        JurisdictionService.ReconcilePlan plan = jurisdictionSvc.planReconcile(uuid, req.getJurisdictions(), tenantId);
        JurisdictionService.ReconcileResult reconciled = tx.execute(status -> {
            repo.update(existing, expectedVersion);
            return jurisdictionSvc.applyReconcile(uuid, plan, tenantId, userId);
        });
        existing.setVersion(expectedVersion + 1); // reflect the bump in the response

        EmployeeResponse resp = toEmployeeResponse(existing, tenantId);
        jurisdictionSvc.publishReconciled(tenantId, reconciled);
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

    /**
     * Hard delete in a single statement: the employee's jurisdictions go with it through the foreign
     * key's ON DELETE CASCADE, so the statement is atomic on its own and the event only goes out
     * once it has committed.
     */
    public void hardDeleteEmployee(String uuid, String tenantId) {
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
     * supplied (null → left untouched), validated before the write transaction opens, as for PUT.
     */
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

        JurisdictionService.ReconcilePlan plan = req.getJurisdictions() == null
                ? null : jurisdictionSvc.planReconcile(uuid, req.getJurisdictions(), tenantId);
        JurisdictionService.ReconcileResult reconciled = tx.execute(status -> {
            repo.patch(uuid, tenantId, patch, expectedVersion);
            return plan == null ? null : jurisdictionSvc.applyReconcile(uuid, plan, tenantId, userId);
        });

        EmployeeResponse resp = getEmployeeByUUID(uuid, tenantId);
        if (reconciled != null) {
            jurisdictionSvc.publishReconciled(tenantId, reconciled);
        }
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
        if (!existing.getIsActive()) {
            throw new CustomException(ErrorCodes.EMPLOYEE_ALREADY_INACTIVE, "employee is already inactive", HttpStatus.CONFLICT);
        }
        int expectedVersion = existing.getVersion();
        existing.setIsActive(false);
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
        if (existing.getIsActive()) {
            throw new CustomException(ErrorCodes.EMPLOYEE_ALREADY_ACTIVE, "employee is already active", HttpStatus.CONFLICT);
        }
        int expectedVersion = existing.getVersion();
        existing.setIsActive(true);
        existing.getAuditDetails().setModifiedBy(userId);
        existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());
        repo.update(existing, expectedVersion);
        businessMetrics.recordEmployeeReactivated(tenantId);
        return getEmployeeByUUID(uuid, tenantId);
    }
}
