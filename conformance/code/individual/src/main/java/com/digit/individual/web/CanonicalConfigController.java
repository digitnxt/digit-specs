package com.digit.individual.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical (envelope-style) tenant validation config resource. Delegates to {@link ConfigController};
 * see {@link CanonicalIndividualController} for the envelope contract.
 */
@RestController
@RequestMapping("/v3/${individual.server.canonical-api-prefix:canonical}/configs")
public class CanonicalConfigController {

    private final ConfigController delegate;
    private final CanonicalDelegate canonical;

    public CanonicalConfigController(ConfigController delegate, CanonicalDelegate canonical) {
        this.delegate = delegate;
        this.canonical = canonical;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody(required = false) byte[] body,
                                                      HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.upsert(
                envelope.tenantId(),
                envelope.userId(),
                canonical.payloadBytes(envelope),
                canonical.withMetadataHeaders(request, envelope)));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.get(envelope.tenantId()));
    }
}
