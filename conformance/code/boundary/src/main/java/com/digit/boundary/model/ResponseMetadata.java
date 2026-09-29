package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata envelope returned on every response of the canonical API
 * ({contextPath}/v3/{canonical-prefix}). {@code tenantId}, {@code msgId}, {@code requestId} and
 * {@code correlationId} are propagated unchanged from the incoming {@link RequestMetadata};
 * {@code ts} is the response generation time (the body counterpart of the legacy
 * X-Response-Timestamp header).
 */
public class ResponseMetadata {

    /** Epoch timestamp in milliseconds indicating when the response was generated. */
    @JsonProperty("ts")
    private Long ts;

    /** Message identifier propagated unchanged from the request. */
    @JsonProperty("msgId")
    private String msgId;

    /** Request identifier propagated unchanged from the request. */
    @JsonProperty("requestId")
    private String requestId;

    /** Distributed-tracing id propagated unchanged from the request. */
    @JsonProperty("correlationId")
    private String correlationId;

    /** Tenant the request was served for. */
    @JsonProperty("tenantId")
    private String tenantId;

    public ResponseMetadata() {}

    /** Builds the response metadata for a request: propagated ids + the given response timestamp. */
    public static ResponseMetadata from(RequestMetadata request, long ts) {
        ResponseMetadata metadata = new ResponseMetadata();
        metadata.setTs(ts);
        metadata.setMsgId(request.getMsgId());
        metadata.setRequestId(request.getRequestId());
        metadata.setCorrelationId(request.getCorrelationId());
        metadata.setTenantId(request.getTenantId());
        return metadata;
    }

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
}
