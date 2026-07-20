package com.digit.employee.constants;

/** Header names (keys). Mirrors Go internal/middleware/headers.go + constants. */
public final class Headers {
    private Headers() {}

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String REQUEST_ID = "X-Request-ID";
    public static final String AUTHORIZATION = "Authorization";
}
