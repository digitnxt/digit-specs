package com.digit.individual.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
 * Canonical (envelope-style) individuals resource: request context travels in the body's
 * RequestMetadata block instead of headers, and responses are wrapped as
 * {@code {"ResponseMetadata": {...}, "data": ...}}.
 *
 * <p>Every handler delegates to {@link IndividualController}, which keeps all validation and business
 * logic in one place. Query parameters are passed through untouched — including the raw String forms
 * the delegate parses by hand to report a 400 rather than a type-mismatch 500.
 */
@RestController
@RequestMapping("/v3/${individual.server.canonical-api-prefix:canonical}/individuals")
public class CanonicalIndividualController {

    private final IndividualController delegate;
    private final CanonicalDelegate canonical;

    public CanonicalIndividualController(IndividualController delegate, CanonicalDelegate canonical) {
        this.delegate = delegate;
        this.canonical = canonical;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) byte[] body,
                                                      HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.create(
                envelope.tenantId(),
                envelope.userId(),
                canonical.payloadBytes(envelope),
                canonical.withMetadataHeaders(request, envelope)));
    }

    /** {@code userId} here is the search filter, not the caller — the caller comes from the metadata. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestBody(required = false) byte[] body,
            @RequestParam(value = "id", required = false) List<String> id,
            @RequestParam(value = "individualId", required = false) List<String> individualId,
            @RequestParam(value = "userId", required = false) List<String> userId,
            @RequestParam(value = "givenName", required = false) String givenName,
            @RequestParam(value = "mobileNumber", required = false) String mobileNumber,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "dateOfBirth", required = false) String dateOfBirth,
            @RequestParam(value = "includeDeleted", required = false, defaultValue = "false") String includeDeletedRaw,
            @RequestParam(value = "page", required = false) String pageRaw,
            @RequestParam(value = "size", required = false) String sizeRaw,
            HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.search(
                envelope.tenantId(), id, individualId, userId, givenName, mobileNumber, gender, dateOfBirth,
                includeDeletedRaw, pageRaw, sizeRaw,
                canonical.withMetadataHeaders(request, envelope)));
    }

    @GetMapping("/exists")
    public ResponseEntity<Map<String, Object>> exists(
            @RequestBody(required = false) byte[] body,
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "individualId", required = false) String individualId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "givenName", required = false) String givenName,
            @RequestParam(value = "mobileNumber", required = false) String mobileNumber,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "dateOfBirth", required = false) String dateOfBirth,
            @RequestParam(value = "includeDeleted", required = false, defaultValue = "false") String includeDeletedRaw) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.exists(
                envelope.tenantId(), id, individualId, userId, givenName, mobileNumber, gender, dateOfBirth,
                includeDeletedRaw));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id,
                                                   @RequestBody(required = false) byte[] body) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, false);
        return canonical.envelope(envelope, delegate.get(envelope.tenantId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") String id,
                                                      @RequestBody(required = false) byte[] body,
                                                      HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.update(
                envelope.tenantId(),
                envelope.userId(),
                id,
                canonical.payloadBytes(envelope),
                canonical.withMetadataHeaders(request, envelope)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") String id,
                                                      @RequestBody(required = false) byte[] body,
                                                      HttpServletRequest request) {
        CanonicalDelegate.Envelope envelope = canonical.parse(body, true);
        return canonical.envelope(envelope, delegate.delete(
                envelope.tenantId(),
                envelope.userId(),
                id,
                canonical.withMetadataHeaders(request, envelope)));
    }
}
