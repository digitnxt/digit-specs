package com.digit.accesscontrol.model;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * PATCH-style partial update of a JBAC rule. Mirrors Go UpdateJbacRuleRequest.
 * Populated manually from the JSON tree by the controller.
 */
public class UpdateJbacRuleRequest {

    /** Top-level fields that must not accept JSON null. Mirrors Go JbacNonNullableUpdateFields. */
    public static final List<String> NON_NULLABLE_FIELDS =
            List.of("name", "pathPattern", "methods", "enforcement", "parentImpliesChildren");

    private String name;
    private String pathPattern;
    private List<String> methods;
    private String enforcement;
    private Boolean parentImpliesChildren;
    private Nullable<JsonNode> extractJurisdiction = new Nullable<>();
    private Nullable<String> description = new Nullable<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPathPattern() { return pathPattern; }
    public void setPathPattern(String pathPattern) { this.pathPattern = pathPattern; }
    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }
    public String getEnforcement() { return enforcement; }
    public void setEnforcement(String enforcement) { this.enforcement = enforcement; }
    public Boolean getParentImpliesChildren() { return parentImpliesChildren; }
    public void setParentImpliesChildren(Boolean parentImpliesChildren) { this.parentImpliesChildren = parentImpliesChildren; }
    public Nullable<JsonNode> getExtractJurisdiction() { return extractJurisdiction; }
    public void setExtractJurisdiction(Nullable<JsonNode> extractJurisdiction) { this.extractJurisdiction = extractJurisdiction; }
    public Nullable<String> getDescription() { return description; }
    public void setDescription(Nullable<String> description) { this.description = description; }
}
