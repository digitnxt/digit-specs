package com.digit.individual.model;

/** Per-request context carrying header-derived values. Mirrors Go internal/models RequestContext. */
public class RequestContext {
    private final String tenantId;
    private final String userId;
    private final String requestId;

    public RequestContext(String tenantId, String userId, String requestId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.requestId = requestId;
    }

    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getRequestId() { return requestId; }
}
