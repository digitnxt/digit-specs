package com.digit.boundary.model;

import java.util.List;

/** Search criteria for boundaries. Mirrors Go models.BoundarySearchCriteria. */
public class BoundarySearchCriteria {

    private String tenantId;
    private List<String> codes;
    private int limit;
    private int offset;
    // Geo search: when both are set, results are limited to boundaries whose
    // geom (PostGIS, SRID 4326) contains the point. Null when not a geo search.
    private Double latitude;
    private Double longitude;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public List<String> getCodes() { return codes; }
    public void setCodes(List<String> codes) { this.codes = codes; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
}
