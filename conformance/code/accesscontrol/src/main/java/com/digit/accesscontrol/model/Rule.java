package com.digit.accesscontrol.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * RBAC rule. Mirrors Go internal/model/rbac_model.go Rule (table access_rbac_rules_v3).
 * JSON field order and omitempty semantics preserved:
 *   id, tenantId, roleNames, httpMethod, path, effect, priority, enabled,
 *   constraints (omitempty), description (omitempty), requestId (omitempty), auditDetails.
 */
@JsonPropertyOrder({"id", "tenantId", "roleNames", "httpMethod", "path", "effect",
        "priority", "enabled", "constraints", "description", "requestId", "auditDetails"})
public class Rule {

    private String id;
    private String tenantId;
    private List<String> roleNames;
    private String httpMethod;
    private String path;
    private String effect;
    private int priority;
    private boolean enabled;

    // json.RawMessage with omitempty: emitted only when non-null/non-empty.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode constraints;

    // string with omitempty: emitted only when non-empty.
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String requestId;

    private AuditDetail auditDetails = new AuditDetail();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public List<String> getRoleNames() { return roleNames; }
    public void setRoleNames(List<String> roleNames) { this.roleNames = roleNames; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public JsonNode getConstraints() { return constraints; }
    public void setConstraints(JsonNode constraints) { this.constraints = constraints; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetail getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetail auditDetails) { this.auditDetails = auditDetails; }
}
