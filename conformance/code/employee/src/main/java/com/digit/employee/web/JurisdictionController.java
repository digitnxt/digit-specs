package com.digit.employee.web;

import com.digit.employee.constants.Headers;
import com.digit.employee.model.CreateJurisdictionRequest;
import com.digit.employee.model.JurisdictionResponse;
import com.digit.employee.model.JurisdictionSearchCriteria;
import com.digit.employee.model.UpdateJurisdictionRequest;
import com.digit.employee.service.JurisdictionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Jurisdiction endpoints — nested under the owning employee, mirroring Go
 * internal/router/router.go + handler/jurisdiction.go (post-8749c30e):
 * {@code /v3/employees/{employeeId}/jurisdictions[/{jurisdictionId}]}. Responses are bare
 * (unwrapped) objects/arrays, matching Go.
 */
@RestController
@RequestMapping("${employee.server.context-path:/employee}/v3/employees/{employeeId}/jurisdictions")
public class JurisdictionController {

    private final JurisdictionService svc;
    private final ObjectMapper objectMapper;

    public JurisdictionController(JurisdictionService svc, ObjectMapper objectMapper) {
        this.svc = svc;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<JurisdictionResponse> createJurisdiction(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @PathVariable("employeeId") String employeeId,
            @RequestBody(required = false) byte[] body) {
        ControllerSupport.requireUUID(employeeId, "Invalid employee UUID");
        CreateJurisdictionRequest req = ControllerSupport.parseBody(objectMapper, body, CreateJurisdictionRequest.class);
        JurisdictionResponse resp = svc.createJurisdiction(employeeId, req, tenantId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    public ResponseEntity<List<JurisdictionResponse>> searchJurisdictions(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @PathVariable("employeeId") String employeeId,
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        ControllerSupport.requireUUID(employeeId, "Invalid employee UUID");
        ControllerSupport.validatePaging(limit, offset);
        if (ids != null) {
            for (String s : ids) {
                ControllerSupport.requireUUID(s, "Invalid id: " + s);
            }
        }
        JurisdictionSearchCriteria criteria = new JurisdictionSearchCriteria();
        criteria.setIds(ids);
        criteria.setIsActive(isActive);
        criteria.setLimit(limit);
        criteria.setOffset(offset);
        criteria.setTenantId(tenantId);
        return ResponseEntity.ok(svc.searchJurisdictions(employeeId, criteria));
    }

    @GetMapping("/{jurisdictionId}")
    public ResponseEntity<JurisdictionResponse> getJurisdictionByUUID(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @PathVariable("employeeId") String employeeId,
            @PathVariable("jurisdictionId") String jurisdictionId) {
        ControllerSupport.requireUUID(employeeId, "Invalid employee UUID");
        ControllerSupport.requireUUID(jurisdictionId, "Invalid jurisdiction UUID");
        return ResponseEntity.ok(svc.getJurisdictionByUUID(employeeId, jurisdictionId, tenantId));
    }

    @PutMapping("/{jurisdictionId}")
    public ResponseEntity<JurisdictionResponse> updateJurisdiction(
            @RequestHeader(value = Headers.TENANT_ID) String tenantId,
            @RequestHeader(value = Headers.USER_ID) String userId,
            @PathVariable("employeeId") String employeeId,
            @PathVariable("jurisdictionId") String jurisdictionId,
            @RequestBody(required = false) byte[] body) {
        ControllerSupport.requireUUID(employeeId, "Invalid employee UUID");
        ControllerSupport.requireUUID(jurisdictionId, "Invalid jurisdiction UUID");
        UpdateJurisdictionRequest req = ControllerSupport.parseBody(objectMapper, body, UpdateJurisdictionRequest.class);
        JurisdictionResponse resp = svc.updateJurisdiction(employeeId, jurisdictionId, req, tenantId, userId);
        return ResponseEntity.ok(resp);
    }
}
