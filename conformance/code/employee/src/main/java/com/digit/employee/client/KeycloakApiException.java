package com.digit.employee.client;

/**
 * Carries the HTTP status and body from a non-success Keycloak admin response, so the service can map
 * it precisely (e.g. 409 → conflict on user create, 403 → forbidden on role assignment). Mirrors the
 * Go {@code keycloak.APIError} / {@code ErrUserConflict}. Unchecked so it flows through the existing
 * clients' catch-and-rethrow pattern.
 */
public class KeycloakApiException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public KeycloakApiException(int statusCode, String body) {
        super("keycloak returned status=" + statusCode + " body=" + body);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
}