package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Node in the hierarchical relationship response tree. Mirrors Go models.EnrichedBoundary:
 * {@code children,omitempty}, {@code auditDetails,omitempty}, {@code parent} excluded ({@code json:"-"}).
 */
@JsonPropertyOrder({"id", "code", "boundaryType", "children", "auditDetails"})
public class EnrichedBoundary {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("code")
    private String code = "";

    @JsonProperty("boundaryType")
    private String boundaryType = "";

    @JsonProperty("children")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EnrichedBoundary> children = new ArrayList<>();

    @JsonProperty("auditDetails")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AuditDetails auditDetails;

    @JsonIgnore
    private String parent = "";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getBoundaryType() { return boundaryType; }
    public void setBoundaryType(String boundaryType) { this.boundaryType = boundaryType; }
    public List<EnrichedBoundary> getChildren() { return children; }
    public void setChildren(List<EnrichedBoundary> children) { this.children = children; }
    public AuditDetails getAuditDetails() { return auditDetails; }
    public void setAuditDetails(AuditDetails auditDetails) { this.auditDetails = auditDetails; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
}
