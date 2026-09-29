package com.digit.account.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Plumbing shared by the canonical controllers: parses the envelope and wraps delegate responses.
 *
 * <p>Canonical handlers hold no business logic — they delegate to the header-based controller, which
 * remains the single source of validation.
 *
 * <p>Nothing is enforced here. Account is the tenant registry itself, so its handlers take no tenant
 * header: X-Client-Id and X-Request-Id are optional throughout, and the config handlers validate the
 * tenant code themselves. Blank metadata values are normalised to null so a delegate sees exactly what
 * an absent header would have given it.
 */
@Component
class CanonicalDelegate {

    private final ObjectMapper mapper;

    CanonicalDelegate(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** {@code tenantCode} maps to X-Tenant-Id, {@code clientId} to X-Client-Id (the acting caller). */
    record Envelope(JsonNode root, JsonNode metadata, String tenantCode, String clientId, String requestId) {}

    Envelope parse(byte[] body) {
        JsonNode root = readTree(body);
        JsonNode metadata = CanonicalSupport.requestMetadata(root);
        return new Envelope(root, metadata,
                CanonicalSupport.tenantId(metadata),
                nullIfBlank(CanonicalSupport.userId(metadata)),
                nullIfBlank(CanonicalSupport.requestId(metadata)));
    }

    /**
     * Re-serializes the domain payload for handlers that parse raw bytes, so their parse and binding
     * errors stay identical. An absent payload stays empty, which those handlers report as "EOF".
     */
    byte[] payloadBytes(Envelope envelope) {
        JsonNode payload = CanonicalSupport.payload(envelope.root());
        if (payload == null || payload.isNull()) {
            return new byte[0];
        }
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new CustomException("BAD_REQUEST", "Invalid request body: " + e.getMessage());
        }
    }

    /** Wraps a delegate response, preserving its status. */
    ResponseEntity<Map<String, Object>> envelope(Envelope envelope, ResponseEntity<?> response) {
        HttpStatusCode status = response.getStatusCode();
        // 204 carries no body, so the envelope has to answer 200 with a null payload instead.
        if (status.value() == HttpStatus.NO_CONTENT.value()) {
            return ResponseEntity.ok(CanonicalSupport.wrap(envelope.metadata(), null));
        }
        return ResponseEntity.status(status).body(CanonicalSupport.wrap(envelope.metadata(), response.getBody()));
    }

    private JsonNode readTree(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new CustomException("BAD_REQUEST", "Invalid request body: " + e.getMessage());
        }
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
