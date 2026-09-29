package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request to create or update a boundary relationship. Mirrors Go models.BoundaryRelationshipRequest. */
public class BoundaryRelationshipRequest {

    @JsonProperty("relationship")
    private BoundaryRelationship relationship = new BoundaryRelationship();

    public BoundaryRelationship getRelationship() { return relationship; }
    public void setRelationship(BoundaryRelationship relationship) { this.relationship = relationship; }
}
