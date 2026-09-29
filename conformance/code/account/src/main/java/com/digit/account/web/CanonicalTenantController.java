package com.digit.account.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical (envelope-style) tenant resource: request context travels in the body's RequestMetadata
 * block instead of headers, and responses are wrapped as
 * {@code {"ResponseMetadata": {...}, "data": ...}}.
 *
 * <p>Every handler delegates to {@link TenantController}, which keeps all validation and business logic
 * in one place. {@code userInfo} maps to X-Client-Id. The envelope's tenant maps to X-Tenant-Id and is
 * optional on every handler here: this service is the tenant registry, so a platform caller operates
 * across tenants, while a caller that does carry one is confined to it.
 */
@RestController
@RequestMapping("/v3/${account.server.canonical-api-prefix:canonical}")
public class CanonicalTenantController {

    private final TenantController delegate;
    private final CanonicalDelegate canonical;

    public CanonicalTenantController(TenantController delegate, CanonicalDelegate canonical) {
        this.delegate = delegate;
        this.canonical = canonical;
    }

    @PostMapping("/tenants")
    public ResponseEntity<Map<String, Object>> createTenant(@RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.createTenant(
                envelope.clientId(), envelope.requestId(), canonical.payloadBytes(envelope)));
    }

    @GetMapping("/tenants")
    public ResponseEntity<Map<String, Object>> listTenants(
            @RequestBody(required = false) byte[] body,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "isActive", required = false) String isActive,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope,
                delegate.listTenants(envelope.tenantCode(), code, name, email, isActive, page, size));
    }

    @PutMapping("/tenants/{id}")
    public ResponseEntity<Map<String, Object>> updateTenant(@PathVariable("id") String id,
                                                            @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.updateTenant(
                id, envelope.tenantCode(), envelope.clientId(), envelope.requestId(),
                canonical.payloadBytes(envelope)));
    }

    @DeleteMapping("/tenants/{id}")
    public ResponseEntity<Map<String, Object>> deleteAccount(@PathVariable("id") String id,
                                                             @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope,
                delegate.deleteAccount(id, envelope.tenantCode(), envelope.clientId()));
    }

    @PostMapping("/tenants/registrations")
    public ResponseEntity<Map<String, Object>> createSignup(@RequestBody(required = false) byte[] body,
                                                            HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.createSignup(
                envelope.clientId(), envelope.requestId(), request, canonical.payloadBytes(envelope)));
    }

    @PostMapping("/tenants/registrations/verify")
    public ResponseEntity<Map<String, Object>> verifySignup(@RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.verifySignup(
                envelope.clientId(), envelope.requestId(), canonical.payloadBytes(envelope)));
    }

    @PostMapping("/tenants/registrations/resend")
    public ResponseEntity<Map<String, Object>> resendSignupOtp(@RequestBody(required = false) byte[] body,
                                                               HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.resendSignupOtp(request, canonical.payloadBytes(envelope)));
    }
}
