package com.digit.accesscontrol.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * JBAC rule. Mirrors Go internal/model/jbac_model.go JbacRule (table access_jbac_rules_v3).
 * JSON field order + omitempty semantics preserved:
 *   id, tenantId, name, pathPattern, methods, enforcement, parentImpliesChildren,
 *   extractJurisdiction (omitempty), description (omitempty), requestId (omitempty), auditDetails.
 */
@JsonPropertyOrder({"id", "tenantId", "name", "pathPattern", "methods", "enforcement",
        "parentImpliesChildren", "extractJurisdiction", "description", "requestId", "auditDetails"})
public class JbacRule {

    private String id;
    private String tenantId;
    private String name;
    private String pathPattern;
    private List<String> methods;
    private String enforcement;
    private boolean parentImpliesChildren;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode extractJurisdiction;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String description;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String requestId;

    private AuditDetail auditDetails = new AuditDetail();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPathPattern() { return pathPattern; }
    public void setPathPattern(String pathPattern) { this.pathPattern = pathPattern; }
    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }
    public String getEnforcement() { return enforcement; }
    public void setEnforcement(String enforcement) { this.enforcement = enforcement; }
    public boolean isParentImpliesChildren() { return parentImpliesChildren; }
    public void setParentImpliesChildren(boolean parentImpliesChildren) { this.parentImpliesChildren = parentImpliesChildren; }
    public JsonNode getExtractJurisdiction() { return extractJurisdiction; }
    public void setExtractJurisdiction(JsonNode extractJurisdiction) { this.extractJurisdiction = extractJurisdiction; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetail getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetail auditDetails) { this.auditDetails = auditDetails; }
}
