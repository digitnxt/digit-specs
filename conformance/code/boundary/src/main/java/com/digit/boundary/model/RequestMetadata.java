package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Standard metadata envelope carried by every request payload of the canonical API
 * ({contextPath}/v3/{canonical-prefix}). Replaces the custom X-* request headers used in
 * specification version 3.0.0 (X-Tenant-Id, X-Time-Stamp, X-Request-Id, X-Correlation-ID,
 * X-User-ID).
 *
 * <p>{@code userInfo} is the decoded JWT claims of the authenticated user, kept as a plain map
 * (no dedicated POJO); the {@code sub} claim carries the user id — see {@link #getUserId()}.
 */
public class RequestMetadata {

    /** Epoch timestamp in milliseconds indicating when the request was made. */
    @NotNull
    @Min(1_000_000_000_000L)
    @Max(9_999_999_999_999L)
    @JsonProperty("ts")
    private Long ts;

    /** Unique message identifier, typically used to carry the locale of the request. */
    @Size(min = 2, max = 64)
    @JsonProperty("msgId")
    private String msgId;

    /** Unique identifier for tracking each request. */
    @Size(min = 2, max = 64)
    @JsonProperty("requestId")
    private String requestId;

    /** Distributed-tracing id, propagated unchanged through the call chain. */
    @Size(min = 2, max = 64)
    @JsonProperty("correlationId")
    private String correlationId;

    /** Identifies the tenant making the request. */
    @NotBlank
    @Size(min = 2, max = 64)
    @JsonProperty("tenantId")
    private String tenantId;

    /** Decoded JWT claims of the authenticated user ({@code sub} = user id). */
    @JsonProperty("userInfo")
    private Map<String, Object> userInfo;

    public Long getTs() { return ts; }
    public void setTs(Long ts) { this.ts = ts; }
    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Map<String, Object> getUserInfo() { return userInfo; }
    public void setUserInfo(Map<String, Object> userInfo) { this.userInfo = userInfo; }

    /**
     * User id taken from the {@code sub} claim of {@link #getUserInfo()} — the canonical-API
     * equivalent of the legacy X-User-ID header. Returns {@code null} when the claim is absent
     * or not a string.
     */
    public String getUserId() {
        Object sub = userInfo == null ? null : userInfo.get("sub");
        return sub instanceof String s ? s : null;
    }
}
