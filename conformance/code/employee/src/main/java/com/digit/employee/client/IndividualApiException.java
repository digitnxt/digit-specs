package com.digit.employee.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Carries the HTTP status and body from a non-success individual-service response, so the service can
 * map a 4xx (bad individual payload / duplicate) differently from a 5xx (dependency failure). Mirrors
 * the Go {@code individual.APIError}.
 */
public class IndividualApiException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int statusCode;
    private final String body;

    public IndividualApiException(int statusCode, String body) {
        super("individual returned status=" + statusCode + " body=" + body);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }

    /** The first {@code message} from the individual service's {@code [{"code","message"}]} body; null when absent. */
    public String errorMessage() {
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode first = node.isArray() ? node.path(0) : node;
            String message = first.path("message").asText("");
            return message.isEmpty() ? null : message;
        } catch (Exception e) {
            return null;
        }
    }
}
