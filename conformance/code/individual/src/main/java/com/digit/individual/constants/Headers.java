package com.digit.individual.constants;

/** Header names. Mirrors Go internal/middleware (X-Tenant-ID, X-User-ID, X-Request-Id). */
public final class Headers {
    private Headers() {}

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String REQUEST_ID = "X-Request-Id";
}
