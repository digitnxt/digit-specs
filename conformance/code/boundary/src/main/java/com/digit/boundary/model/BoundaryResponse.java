package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Response containing boundaries. Mirrors Go models.BoundaryResponse. */
public class BoundaryResponse {

    @JsonProperty("boundary")
    private List<Boundary> boundary;

    public BoundaryResponse() {}

    public BoundaryResponse(List<Boundary> boundary) {
        this.boundary = boundary;
    }

    public List<Boundary> getBoundary() { return boundary; }
    public void setBoundary(List<Boundary> boundary) { this.boundary = boundary; }
}
