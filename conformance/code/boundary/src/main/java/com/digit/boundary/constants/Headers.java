package com.digit.boundary.constants;

/** Header names. Mirrors Go internal/handlers/headers.go. */
public final class Headers {
    private Headers() {}

    public static final String TENANT_ID = "X-Tenant-Id";
    public static final String USER_ID = "X-User-ID";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String KONG_REQUEST_ID = "X-Kong-Request-Id";
}
