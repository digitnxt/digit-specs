package com.digit.employee.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Carries the HTTP status and body from a non-success Keycloak admin response, so the service can map
 * it precisely (e.g. 409 → conflict on user create, 403 → forbidden on role assignment). Mirrors the
 * Go {@code keycloak.APIError} / {@code ErrUserConflict}. Unchecked so it flows through the existing
 * clients' catch-and-rethrow pattern.
 */
public class KeycloakApiException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int statusCode;
    private final String body;

    public KeycloakApiException(int statusCode, String body) {
        super("keycloak returned status=" + statusCode + " body=" + body);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }

    /**
     * Keycloak's {@code errorMessage} from a body like {@code {"field":"email","errorMessage":"error-invalid-email"}},
     * prefixed with the field when present; null when the body carries none.
     */
    public String errorMessage() {
        try {
            JsonNode node = JSON.readTree(body);
            String message = node.path("errorMessage").asText("");
            if (message.isEmpty()) {
                return null;
            }
            String field = node.path("field").asText("");
            return field.isEmpty() ? message : field + ": " + message;
        } catch (Exception e) {
            return null;
        }
    }
}