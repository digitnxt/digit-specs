package com.digit.employee.web;

import com.digit.employee.constants.ErrorCodes;
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
 * remains the single source of validation. HeaderInterceptor is excluded from the canonical path group
 * (it enforces headers the envelope replaces), so the tenant/user checks it would have applied are
 * reproduced here against the metadata block instead.
 */
@Component
class CanonicalDelegate {

    private final ObjectMapper mapper;

    CanonicalDelegate(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record Envelope(JsonNode root, JsonNode metadata, String tenantId, String userId, String requestId) {}

    /**
     * Parses and validates the envelope. {@code requireUserId} mirrors HeaderInterceptor: tenant always,
     * user on writes (it stamps createdBy/modifiedBy, so read-only GETs do not need it).
     */
    Envelope parse(byte[] body, boolean requireUserId) {
        JsonNode root = readTree(body);
        JsonNode metadata = CanonicalSupport.requestMetadata(root);
        String tenantId = CanonicalSupport.tenantId(metadata);
        if (tenantId == null) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "RequestMetadata.tenantId is required",
                    HttpStatus.BAD_REQUEST);
        }
        String userId = CanonicalSupport.userId(metadata);
        if (requireUserId && userId.isBlank()) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "RequestMetadata.userInfo is required",
                    HttpStatus.BAD_REQUEST);
        }
        return new Envelope(root, metadata, tenantId, userId, CanonicalSupport.requestId(metadata));
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
            throw new CustomException(ErrorCodes.INVALID_REQUEST, e.getMessage());
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
            throw new CustomException(ErrorCodes.INVALID_REQUEST, "Invalid request body: " + e.getMessage());
        }
    }
}
