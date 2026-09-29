package com.digit.individual.web;

import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.constants.Headers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing shared by the canonical controllers: parses the envelope, and adapts a canonical request so
 * a header-based handler can serve it unchanged.
 *
 * <p>Canonical handlers hold no business logic — they delegate to the header-based controller, which
 * remains the single source of validation. HeaderInterceptor is excluded from the canonical path group
 * (it enforces headers the envelope replaces), so the tenant/user checks it would have applied are
 * reproduced here against the metadata block instead.
 */
@Component
class CanonicalDelegate {

    private final JsonMapper mapper;

    CanonicalDelegate(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parsed envelope. {@code requireUserId} mirrors HeaderInterceptor: tenant always, user on writes
     * (a GET stamps no createdBy/modifiedBy, so it needs none).
     */
    record Envelope(JsonNode root, JsonNode metadata, String tenantId, String userId, String requestId) {}

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
            throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                    "RequestMetadata.userInfo is required", HttpStatus.BAD_REQUEST);
        }
        return new Envelope(root, metadata, tenantId, userId, CanonicalSupport.requestId(metadata));
    }

    /**
     * Re-serializes the domain payload for handlers that parse raw bytes, so their strict mapper still
     * rejects unknown fields. An absent payload stays empty, which those handlers report as "EOF".
     */
    byte[] payloadBytes(Envelope envelope) {
        JsonNode payload = CanonicalSupport.payload(envelope.root());
        if (payload == null || payload.isNull()) {
            return new byte[0];
        }
        return mapper.writeValueAsBytes(payload);
    }

    /**
     * Serves the metadata values under their header names, so a handler reading X-Request-Id straight
     * off the request (see IndividualController.ctx) sees the envelope's value. Anything not carried by
     * the envelope falls through to the real request.
     */
    HttpServletRequest withMetadataHeaders(HttpServletRequest request, Envelope envelope) {
        // Servlet header names are case-insensitive, and callers may not use the Headers constants' casing.
        Map<String, String> injected = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        put(injected, Headers.TENANT_ID, envelope.tenantId());
        put(injected, Headers.USER_ID, envelope.userId());
        put(injected, Headers.REQUEST_ID, envelope.requestId());
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                String value = injected.get(name);
                return value != null ? value : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                String value = injected.get(name);
                return value != null ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
            }
        };
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
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "Invalid request body: " + e.getMessage(),
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static void put(Map<String, String> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }
}
