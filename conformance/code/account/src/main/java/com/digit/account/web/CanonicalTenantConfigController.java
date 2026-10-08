package com.digit.account.web;

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
 * Canonical (envelope-style) tenant configuration resource. Delegates to {@link TenantConfigController};
 * see {@link CanonicalTenantController} for the envelope contract. Here {@code tenantId} carries the
 * owning tenant code, which the delegate validates.
 */
@RestController
@RequestMapping("/v3/${account.server.canonical-api-prefix:canonical}")
public class CanonicalTenantConfigController {

    private final TenantConfigController delegate;
    private final CanonicalDelegate canonical;

    public CanonicalTenantConfigController(TenantConfigController delegate, CanonicalDelegate canonical) {
        this.delegate = delegate;
        this.canonical = canonical;
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> createTenantConfig(@RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.createTenantConfig(
                envelope.tenantCode(), envelope.clientId(), envelope.requestId(),
                canonical.payloadBytes(envelope)));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> listTenantConfigs(
            @RequestBody(required = false) byte[] body,
            @RequestParam(value = "configKey", required = false) String configKey,
            @RequestParam(value = "isActive", required = false) String isActive,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.listTenantConfigs(
                envelope.tenantCode(), configKey, isActive, page, size));
    }

    @PutMapping("/config/{id}")
    public ResponseEntity<Map<String, Object>> updateTenantConfig(@PathVariable("id") String id,
                                                                  @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body);
        return canonical.envelope(envelope, delegate.updateTenantConfig(
                id, envelope.tenantCode(), envelope.clientId(), envelope.requestId(),
                canonical.payloadBytes(envelope)));
    }
}
