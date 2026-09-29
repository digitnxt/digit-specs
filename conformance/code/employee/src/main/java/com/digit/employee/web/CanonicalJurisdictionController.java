package com.digit.employee.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical (envelope-style) jurisdiction resource, nested under the owning employee. Delegates to
 * {@link JurisdictionController}; see {@link CanonicalEmployeeController} for the envelope contract.
 */
@RestController
@RequestMapping("/v3/${employee.server.canonical-api-prefix:canonical}/employees/{employeeId}/jurisdictions")
public class CanonicalJurisdictionController {

    private final JurisdictionController delegate;
    private final CanonicalDelegate canonical;

    public CanonicalJurisdictionController(JurisdictionController delegate, CanonicalDelegate canonical) {
        this.delegate = delegate;
        this.canonical = canonical;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createJurisdiction(@PathVariable("employeeId") String employeeId,
                                                                  @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.createJurisdiction(
                envelope.tenantId(), envelope.userId(), employeeId, canonical.payloadBytes(envelope)));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> searchJurisdictions(
            @PathVariable("employeeId") String employeeId,
            @RequestBody(required = false) byte[] body,
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.searchJurisdictions(
                envelope.tenantId(), employeeId, ids, isActive, limit, offset));
    }

    @GetMapping("/{jurisdictionId}")
    public ResponseEntity<Map<String, Object>> getJurisdictionByUUID(
            @PathVariable("employeeId") String employeeId,
            @PathVariable("jurisdictionId") String jurisdictionId,
            @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.getJurisdictionByUUID(
                envelope.tenantId(), employeeId, jurisdictionId));
    }

    @PutMapping("/{jurisdictionId}")
    public ResponseEntity<Map<String, Object>> updateJurisdiction(
            @PathVariable("employeeId") String employeeId,
            @PathVariable("jurisdictionId") String jurisdictionId,
            @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.updateJurisdiction(
                envelope.tenantId(), envelope.userId(), employeeId, jurisdictionId,
                canonical.payloadBytes(envelope)));
    }
}
