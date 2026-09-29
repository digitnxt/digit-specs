package com.digit.boundary.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** Request to create or update boundaries. Mirrors Go models.BoundaryRequest. */
public class BoundaryRequest {

    @JsonProperty("boundary")
    private List<Boundary> boundary = new ArrayList<>();

    public List<Boundary> getBoundary() { return boundary; }
    public void setBoundary(List<Boundary> boundary) { this.boundary = boundary; }
}
