package com.digit.account.constants;

/** Header names. Account echoes X-Tenant-Id / X-Client-Id / X-Request-Id when present. */
public final class Headers {
    private Headers() {}

    public static final String TENANT_ID = "X-Tenant-Id";
    public static final String CLIENT_ID = "X-Client-Id";
    public static final String REQUEST_ID = "X-Request-Id";
}
