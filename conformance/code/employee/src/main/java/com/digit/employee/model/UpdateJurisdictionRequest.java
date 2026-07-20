package com.digit.employee.model;

import java.util.List;

/**
 * Request payload for updating/replacing a jurisdiction. Mirrors Go {@code UpdateJurisdictionRequest}.
 * {@code boundaryRelation} is a pointer in Go (null = not supplied); modelled here as a nullable list.
 */
public class UpdateJurisdictionRequest {

    private List<BoundaryRef> boundaryRelation;
    private Boolean isActive;
    // Optimistic-concurrency token the client last read for this jurisdiction. Required (validated in
    // the service); the update compare-and-swaps on it → 409 ROW_VERSION_MISMATCH on staleness.
    // Independent of the owning employee's version.
    private Integer version;

    public List<BoundaryRef> getBoundaryRelation() { return boundaryRelation; }
    public void setBoundaryRelation(List<BoundaryRef> boundaryRelation) { this.boundaryRelation = boundaryRelation; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
