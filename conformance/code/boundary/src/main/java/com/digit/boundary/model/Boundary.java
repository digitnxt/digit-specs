package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tools.jackson.databind.JsonNode;

/**
 * Boundary entity. Mirrors Go internal/models/boundary.Boundary (table boundary_v1).
 * {@code geometry} and {@code additionalAttributes} are raw jsonb (Jackson 3 JsonNode).
 */
@JsonPropertyOrder({"id", "tenantId", "code", "geometry", "additionalAttributes", "requestId", "auditDetails"})
public class Boundary {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("tenantId")
    private String tenantId = "";

    @JsonProperty("code")
    private String code = "";

    // json.RawMessage with no omitempty: always serialized (null when absent).
    @JsonProperty("geometry")
    private JsonNode geometry;

    @JsonProperty("additionalAttributes")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode additionalAttributes;

    @JsonProperty("requestId")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String requestId;

    @JsonProperty("auditDetails")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AuditDetails auditDetails;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public JsonNode getGeometry() { return geometry; }
    public void setGeometry(JsonNode geometry) { this.geometry = geometry; }
    public JsonNode getAdditionalAttributes() { return additionalAttributes; }
    public void setAdditionalAttributes(JsonNode additionalAttributes) { this.additionalAttributes = additionalAttributes; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetails getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetails auditDetails) { this.auditDetails = auditDetails; }
}
