package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Response containing boundary relationships. Mirrors Go models.BoundaryRelationshipResponse. */
public class BoundaryRelationshipResponse {

    @JsonProperty("relationship")
    private List<BoundaryRelationship> relationship;

    public BoundaryRelationshipResponse() {}

    public BoundaryRelationshipResponse(List<BoundaryRelationship> relationship) {
        this.relationship = relationship;
    }

    public List<BoundaryRelationship> getRelationship() { return relationship; }
    public void setRelationship(List<BoundaryRelationship> relationship) { this.relationship = relationship; }
}
