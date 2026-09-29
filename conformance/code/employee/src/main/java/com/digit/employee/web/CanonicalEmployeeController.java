package com.digit.employee.web;

import com.digit.employee.constants.Headers;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * Canonical (envelope-style) employee resource: request context travels in the body's RequestMetadata
 * block instead of headers, and responses are wrapped as
 * {@code {"ResponseMetadata": {...}, "data": ...}}.
 *
 * <p>Every handler delegates to {@link EmployeeController}, which keeps all validation and business
 * logic in one place. Authorization stays a header: it is a bearer credential forwarded to Keycloak,
 * not request context, so the envelope does not carry it.
 */
@RestController
@RequestMapping("/v3/${employee.server.canonical-api-prefix:canonical}/employees")
public class CanonicalEmployeeController {

    private final EmployeeController delegate;
    private final CanonicalDelegate canonical;

    public CanonicalEmployeeController(EmployeeController delegate, CanonicalDelegate canonical) {
        this.delegate = delegate;
        this.canonical = canonical;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createEmployees(
            @RequestHeader(value = Headers.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.createEmployees(
                envelope.tenantId(), envelope.userId(), authHeader, canonical.payloadBytes(envelope)));
    }

    @PostMapping("/onboard")
    public ResponseEntity<Map<String, Object>> onboardEmployee(
            @RequestHeader(value = Headers.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.onboardEmployee(
                envelope.tenantId(), envelope.userId(), authHeader, canonical.payloadBytes(envelope)));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> searchEmployees(
            @RequestHeader(value = Headers.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) byte[] body,
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "codes", required = false) List<String> codes,
            @RequestParam(value = "userIds", required = false) List<String> userIds,
            @RequestParam(value = "statuses", required = false) List<String> statuses,
            @RequestParam(value = "employeeTypes", required = false) List<String> employeeTypes,
            @RequestParam(value = "departments", required = false) List<String> departments,
            @RequestParam(value = "designations", required = false) List<String> designations,
            @RequestParam(value = "dateOfAppointmentFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate dateOfAppointmentFrom,
            @RequestParam(value = "dateOfAppointmentTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate dateOfAppointmentTo,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "limit", required = false) String limitRaw,
            @RequestParam(value = "offset", required = false) String offsetRaw) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.searchEmployees(
                envelope.tenantId(), authHeader, ids, codes, userIds, statuses, employeeTypes, departments,
                designations, dateOfAppointmentFrom, dateOfAppointmentTo, role, isActive, limitRaw, offsetRaw));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getEmployeeByUUID(@PathVariable("id") String id,
                                                                 @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.getEmployeeByUUID(envelope.tenantId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateEmployee(@PathVariable("id") String id,
                                                              @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.updateEmployee(
                envelope.tenantId(), envelope.userId(), id, canonical.payloadBytes(envelope)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> hardDeleteEmployee(@PathVariable("id") String id,
                                                                  @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.hardDeleteEmployee(envelope.tenantId(), id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> patchEmployee(@PathVariable("id") String id,
                                                             @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.patchEmployee(
                envelope.tenantId(), envelope.userId(), id, canonical.payloadBytes(envelope)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateEmployee(@PathVariable("id") String id,
                                                                  @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.deactivateEmployee(
                envelope.tenantId(), envelope.userId(), id));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Map<String, Object>> reactivateEmployee(@PathVariable("id") String id,
                                                                  @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.reactivateEmployee(
                envelope.tenantId(), envelope.userId(), id));
    }
}
