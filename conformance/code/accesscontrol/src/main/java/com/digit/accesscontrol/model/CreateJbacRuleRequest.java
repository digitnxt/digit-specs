package com.digit.accesscontrol.model;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Request to create a JBAC rule. Mirrors Go CreateJbacRuleRequest. */
public class CreateJbacRuleRequest {

    private String name;
    private String pathPattern;
    private List<String> methods;
    private String enforcement;
    private boolean parentImpliesChildren;
    private JsonNode extractJurisdiction;
    private String description;

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
}
