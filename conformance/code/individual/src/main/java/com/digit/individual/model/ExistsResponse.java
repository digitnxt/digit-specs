package com.digit.individual.model;

/** Response for GET /individuals/exists. Mirrors Go ExistsResponse. */
public class ExistsResponse {
    private boolean exists;
    public ExistsResponse(boolean exists) { this.exists = exists; }
    public boolean isExists() { return exists; }
}
