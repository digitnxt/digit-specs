package com.digit.boundary.validator;

import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.GeometryType;

import org.digit.tracer.model.CustomException;
import tools.jackson.databind.JsonNode;

/**
 * GeoJSON geometry validation. Mirrors Go internal/validator/validation.go
 * (validateCoordinates / validateGeometryCollection / validateClosedRing), including the exact
 * error messages and the rule that polygon rings must be closed.
 */
public final class GeometryValidator {
    private GeometryValidator() {}

    /** Validates a full geometry object (the {@code geometry} jsonb). */
    public static void validateGeometry(JsonNode geom) {
        if (geom == null || !geom.isObject()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Invalid geometry JSON");
        }
        JsonNode typeNode = geom.get("type");
        String typeStr = typeNode != null && typeNode.isString() ? typeNode.asString() : null;
        if (typeStr == null || !GeometryType.isValid(typeStr)) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Invalid geometry type: Allowed types are Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon, GeometryCollection");
        }
        if ("GeometryCollection".equals(typeStr)) {
            JsonNode geometries = geom.get("geometries");
            if (geometries == null) {
                throw new CustomException(ErrorCodes.BAD_REQUEST,"GeometryCollection must have 'geometries' array");
            }
            validateGeometryCollection(geometries);
        } else {
            JsonNode coords = geom.get("coordinates");
            if (coords == null) {
                throw new CustomException(ErrorCodes.BAD_REQUEST,"Missing coordinates in geometry");
            }
            validateCoordinates(typeStr, coords);
        }
    }

    private static void validateCoordinates(String typeStr, JsonNode coords) {
        switch (typeStr) {
            case "Point" -> {
                if (!coords.isArray() || coords.size() != 2 || !isNumber(coords.get(0)) || !isNumber(coords.get(1))) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Point geometry must have [x, y] coordinates");
                }
            }
            case "LineString" -> {
                if (!coords.isArray() || coords.size() < 2) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"LineString geometry must have at least 2 coordinate positions");
                }
                for (JsonNode coord : coords) {
                    if (!coord.isArray() || coord.size() != 2 || !isNumber(coord.get(0)) || !isNumber(coord.get(1))) {
                        throw new CustomException(ErrorCodes.BAD_REQUEST,"Each LineString coordinate must be [x, y]");
                    }
                }
            }
            case "Polygon" -> {
                if (!coords.isArray() || coords.isEmpty()) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Polygon geometry must be an array of linear rings");
                }
                for (JsonNode ring : coords) {
                    if (!ring.isArray() || ring.size() < 4) {
                        throw new CustomException(ErrorCodes.BAD_REQUEST,"Each polygon ring must have at least 4 points");
                    }
                    for (JsonNode coord : ring) {
                        if (!coord.isArray() || coord.size() != 2 || !isNumber(coord.get(0)) || !isNumber(coord.get(1))) {
                            throw new CustomException(ErrorCodes.BAD_REQUEST,"Each polygon coordinate must be [x, y]");
                        }
                    }
                    validateClosedRing(ring);
                }
            }
            case "MultiPoint" -> {
                if (!coords.isArray() || coords.isEmpty()) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"MultiPoint geometry must be an array of point coordinates");
                }
                for (JsonNode point : coords) {
                    if (!point.isArray() || point.size() != 2 || !isNumber(point.get(0)) || !isNumber(point.get(1))) {
                        throw new CustomException(ErrorCodes.BAD_REQUEST,"Each MultiPoint coordinate must be [x, y]");
                    }
                }
            }
            case "MultiLineString" -> {
                if (!coords.isArray() || coords.isEmpty()) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"MultiLineString geometry must be an array of LineString coordinates");
                }
                for (JsonNode lineString : coords) {
                    if (!lineString.isArray() || lineString.size() < 2) {
                        throw new CustomException(ErrorCodes.BAD_REQUEST,"Each LineString in MultiLineString must have at least 2 coordinates");
                    }
                    for (JsonNode coord : lineString) {
                        if (!coord.isArray() || coord.size() != 2 || !isNumber(coord.get(0)) || !isNumber(coord.get(1))) {
                            throw new CustomException(ErrorCodes.BAD_REQUEST,"Each MultiLineString coordinate must be [x, y]");
                        }
                    }
                }
            }
            case "MultiPolygon" -> {
                if (!coords.isArray() || coords.isEmpty()) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"MultiPolygon geometry must be an array of polygons");
                }
                for (JsonNode poly : coords) {
                    if (!poly.isArray() || poly.isEmpty()) {
                        throw new CustomException(ErrorCodes.BAD_REQUEST,"Each MultiPolygon must contain polygons");
                    }
                    for (JsonNode ring : poly) {
                        if (!ring.isArray() || ring.size() < 4) {
                            throw new CustomException(ErrorCodes.BAD_REQUEST,"Each polygon ring in MultiPolygon must have at least 4 points");
                        }
                        for (JsonNode coord : ring) {
                            if (!coord.isArray() || coord.size() != 2 || !isNumber(coord.get(0)) || !isNumber(coord.get(1))) {
                                throw new CustomException(ErrorCodes.BAD_REQUEST,"Each MultiPolygon coordinate must be [x, y]");
                            }
                        }
                        validateClosedRing(ring);
                    }
                }
            }
            case "GeometryCollection" ->
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"GeometryCollection should contain 'geometries' array, not 'coordinates'");
            default -> throw new CustomException(ErrorCodes.BAD_REQUEST,"Unsupported geometry type");
        }
    }

    private static void validateGeometryCollection(JsonNode geometries) {
        if (!geometries.isArray() || geometries.isEmpty()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"GeometryCollection must contain at least one geometry");
        }
        for (JsonNode geom : geometries) {
            if (!geom.isObject()) {
                throw new CustomException(ErrorCodes.BAD_REQUEST,"Each geometry in GeometryCollection must be a valid geometry object");
            }
            JsonNode typeNode = geom.get("type");
            String typeStr = typeNode != null && typeNode.isString() ? typeNode.asString() : null;
            if (typeStr == null || !GeometryType.isValid(typeStr)) {
                throw new CustomException(ErrorCodes.BAD_REQUEST,"Invalid geometry type in GeometryCollection");
            }
            if ("GeometryCollection".equals(typeStr)) {
                JsonNode nested = geom.get("geometries");
                if (nested == null) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Nested GeometryCollection must have 'geometries' array");
                }
                validateGeometryCollection(nested);
            } else {
                JsonNode coords = geom.get("coordinates");
                if (coords == null) {
                    throw new CustomException(ErrorCodes.BAD_REQUEST,"Missing coordinates in geometry within GeometryCollection");
                }
                validateCoordinates(typeStr, coords);
            }
        }
    }

    private static void validateClosedRing(JsonNode ring) {
        if (ring.size() < 4) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Polygon ring must have at least 4 points");
        }
        JsonNode first = ring.get(0);
        if (first == null || !first.isArray() || first.size() < 2) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Invalid first coordinate in ring");
        }
        JsonNode last = ring.get(ring.size() - 1);
        if (last == null || !last.isArray() || last.size() < 2) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Invalid last coordinate in ring");
        }
        if (!isNumber(first.get(0)) || !isNumber(first.get(1)) || !isNumber(last.get(0)) || !isNumber(last.get(1))) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Invalid coordinate values in ring");
        }
        if (first.get(0).asDouble() != last.get(0).asDouble() || first.get(1).asDouble() != last.get(1).asDouble()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST,"Polygon ring must be closed: first and last coordinates must be identical");
        }
    }

    /** Go isNumber checks for a float64 (any JSON number). */
    private static boolean isNumber(JsonNode n) {
        return n != null && n.isNumber();
    }
}
