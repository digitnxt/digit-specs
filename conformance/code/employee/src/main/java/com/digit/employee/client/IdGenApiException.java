package com.digit.employee.client;

/**
 * Carries the HTTP status and body from a non-success idgen response, so the service can tell a
 * missing template (404) apart from an idgen failure.
 */
public class IdGenApiException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public IdGenApiException(int statusCode, String body) {
        super("idgen returned status=" + statusCode + " body=" + body);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
}
