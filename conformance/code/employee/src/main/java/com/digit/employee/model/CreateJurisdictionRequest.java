package com.digit.employee.model;

import java.util.List;

/**
 * Request payload for creating a jurisdiction. Mirrors Go {@code CreateJurisdictionRequest}.
 * {@code employeeId} is supplied via the URL path (nested resource) and is therefore absent here.
 */
public class CreateJurisdictionRequest {

    private List<BoundaryRef> boundaryRelation;
    private Boolean isActive;

    public List<BoundaryRef> getBoundaryRelation() { return boundaryRelation; }
    public void setBoundaryRelation(List<BoundaryRef> boundaryRelation) { this.boundaryRelation = boundaryRelation; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
