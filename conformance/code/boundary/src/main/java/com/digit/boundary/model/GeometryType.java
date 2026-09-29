package com.digit.boundary.model;

import java.util.Set;

/** Valid GeoJSON geometry types. Mirrors Go internal/models/geometry.go. */
public final class GeometryType {
    private GeometryType() {}

    private static final Set<String> VALID = Set.of(
            "Point", "LineString", "Polygon",
            "MultiPoint", "MultiLineString", "MultiPolygon",
            "GeometryCollection");

    public static boolean isValid(String t) {
        return VALID.contains(t);
    }
}
