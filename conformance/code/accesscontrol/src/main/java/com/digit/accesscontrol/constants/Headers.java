package com.digit.accesscontrol.constants;

/** Header names (keys). Mirrors the Go gin handlers' c.GetHeader usage. */
public final class Headers {
    private Headers() {}

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String REQUEST_ID = "X-Request-ID";
}
