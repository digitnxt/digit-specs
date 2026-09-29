package com.digit.employee.client;

/**
 * Carries the HTTP status and body from a non-success individual-service response, so the service can
 * map a 4xx (bad individual payload / duplicate) differently from a 5xx (dependency failure). Mirrors
 * the Go {@code individual.APIError}.
 */
public class IndividualApiException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public IndividualApiException(int statusCode, String body) {
        super("individual returned status=" + statusCode + " body=" + body);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
}