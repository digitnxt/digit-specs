package com.digit.boundary.web;

import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryHierarchyRequest;
import com.digit.boundary.model.BoundaryHierarchyResponse;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryRelationshipRequest;
import com.digit.boundary.model.BoundaryRelationshipResponse;
import com.digit.boundary.model.BoundaryRelationshipSearchCriteria;
import com.digit.boundary.model.BoundaryRequest;
import com.digit.boundary.model.BoundaryResponse;
import com.digit.boundary.model.BoundarySearchCriteria;
import com.digit.boundary.model.BoundarySearchResponse;
import com.digit.boundary.model.GeometryType;
import com.digit.boundary.model.HierarchyRelation;
import com.digit.boundary.service.BoundaryHierarchyService;
import com.digit.boundary.service.BoundaryRelationshipService;
import com.digit.boundary.service.BoundaryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Endpoint flows shared by {@link BoundaryController} (legacy, X-* header metadata) and
 * {@link CanonicalBoundaryController} (RequestMetadata in the body). Contains everything the legacy
 * controller did after resolving tenantId/userId/requestId — entity preparation, validation,
 * service invocation and error-to-status mapping — so both API styles converge on the exact same
 * business flow without duplication. Behavior mirrors the Go handlers (see
 * internal/handlers/boundary_handler.go), unchanged by the extraction.
 */
@Component
public class BoundaryEndpointFlows {

    private final BoundaryService boundaryService;
    private final BoundaryHierarchyService hierarchyService;
    private final BoundaryRelationshipService relationshipService;
    private final JsonMapper jsonMapper;

    public BoundaryEndpointFlows(BoundaryService boundaryService, BoundaryHierarchyService hierarchyService,
                                 BoundaryRelationshipService relationshipService, JsonMapper jsonMapper) {
        this.boundaryService = boundaryService;
        this.hierarchyService = hierarchyService;
        this.relationshipService = relationshipService;
        this.jsonMapper = jsonMapper;
    }

    // ----- Boundary -----

    public BoundaryResponse createBoundaries(BoundaryRequest request, String tenantId, String userId,
                                             String requestId) {
        for (Boundary b : request.getBoundary()) {
            b.setId("");
            b.setTenantId(tenantId);
            if (b.getCode() == null || b.getCode().trim().isEmpty()) {
                throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                        "code is required for each boundary", "Invalid request payload");
            }
            validateGeometryType(b.getGeometry());
        }

        // Mirror Go: duplicate code -> 409 CONFLICT, any other failure -> 400 BAD_REQUEST.
        try {
            boundaryService.create(request, tenantId, userId, requestId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Duplicate boundary code")) {
                throw new BoundaryApiException(409, ErrorCodes.CONFLICT, msg, "Failed to create boundaries", null);
            }
            throw new BoundaryApiException(400, ErrorCodes.BAD_REQUEST, msg, "Failed to create boundaries", null);
        }

        return new BoundaryResponse(request.getBoundary());
    }

    public BoundaryResponse searchBoundaries(HttpServletRequest http, String tenantId) {
        BoundarySearchCriteria criteria = new BoundarySearchCriteria();
        criteria.setTenantId(tenantId);

        String[] codes = http.getParameterValues("codes");
        if (codes != null && codes.length > 0) {
            criteria.setCodes(List.of(codes));
        }

        String latStr = http.getParameter("latitude");
        if (notEmpty(latStr)) {
            criteria.setLatitude(parseDoubleOrError(latStr, "Invalid latitude parameter"));
        }
        String lonStr = http.getParameter("longitude");
        if (notEmpty(lonStr)) {
            criteria.setLongitude(parseDoubleOrError(lonStr, "Invalid longitude parameter"));
        }

        if ((codes == null || codes.length == 0) && criteria.getLatitude() == null && criteria.getLongitude() == null) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing required query parameter: codes (or latitude and longitude)", "Invalid request payload");
        }

        String limitStr = http.getParameter("limit");
        if (notEmpty(limitStr)) {
            criteria.setLimit(parseIntOrError(limitStr, "Invalid limit parameter"));
        }
        String offsetStr = http.getParameter("offset");
        if (notEmpty(offsetStr)) {
            criteria.setOffset(parseIntOrError(offsetStr, "Invalid offset parameter"));
        }

        return searchBoundaries(criteria);
    }

    public BoundaryResponse searchBoundaries(BoundarySearchCriteria criteria) {
        validateGeoPair(criteria.getLatitude(), criteria.getLongitude());

        // Mirror Go: any search failure -> 400 with code INTERNAL_SERVER_ERROR.
        List<Boundary> boundaries;
        try {
            boundaries = boundaryService.search(criteria);
        } catch (Exception e) {
            throw new BoundaryApiException(400, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                    "Failed to search boundaries", null);
        }
        return new BoundaryResponse(boundaries);
    }

    public BoundaryResponse updateBoundary(Boundary boundary, String boundaryId, String tenantId, String userId,
                                           String requestId) {
        boundary.setId(boundaryId);
        boundary.setTenantId(tenantId);
        validateGeometryType(boundary.getGeometry());

        BoundaryRequest request = new BoundaryRequest();
        request.setBoundary(new java.util.ArrayList<>(List.of(boundary)));

        // Mirror Go: "does not exist" -> 404 NOT_FOUND, any other failure -> 400 INTERNAL_SERVER_ERROR.
        try {
            boundaryService.update(request, tenantId, userId, requestId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("does not exist")) {
                throw new BoundaryApiException(404, ErrorCodes.NOT_FOUND, msg, "Boundary not found", null);
            }
            throw new BoundaryApiException(400, ErrorCodes.INTERNAL_SERVER_ERROR, msg, "Failed to update boundary", null);
        }

        return new BoundaryResponse(List.of(boundary));
    }

    // ----- Hierarchy -----

    public BoundaryHierarchyResponse createHierarchy(BoundaryHierarchyRequest request, String tenantId, String userId,
                                                     String requestId) {
        request.getHierarchy().setTenantId(tenantId);

        // Mirror Go: duplicate hierarchy type -> 409 CONFLICT, any other failure -> 400 BAD_REQUEST.
        try {
            hierarchyService.create(request, tenantId, userId, requestId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Duplicate hierarchy type")) {
                throw new BoundaryApiException(409, ErrorCodes.CONFLICT, msg, "Failed to create hierarchy", null);
            }
            throw new BoundaryApiException(400, ErrorCodes.BAD_REQUEST, msg, "Failed to create hierarchy", null);
        }

        return new BoundaryHierarchyResponse(List.of(request.getHierarchy()));
    }

    public BoundaryHierarchyResponse getHierarchy(HttpServletRequest http, String tenantId) {
        // hierarchyType is an optional filter: tenant-only searches return every hierarchy.
        return getHierarchy(new BoundaryHierarchySearchCriteria(tenantId,
                orEmpty(http.getParameter("hierarchyType"))));
    }

    public BoundaryHierarchyResponse getHierarchy(BoundaryHierarchySearchCriteria criteria) {
        // Mirror Go: any search failure -> 400 with code INTERNAL_SERVER_ERROR.
        List<BoundaryHierarchy> hierarchies;
        try {
            hierarchies = hierarchyService.search(criteria);
        } catch (Exception e) {
            throw new BoundaryApiException(400, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                    "Failed to get hierarchy", null);
        }
        // Go: GORM leaves the slice nil when no rows match, so an empty result renders as
        // "hierarchy": null, not [].
        return new BoundaryHierarchyResponse(
                hierarchies == null || hierarchies.isEmpty() ? null : hierarchies);
    }

    public BoundaryHierarchyResponse updateHierarchy(BoundaryHierarchyRequest request, String hierarchyId,
                                                     String tenantId, String userId, String requestId) {
        request.getHierarchy().setId(hierarchyId);
        request.getHierarchy().setTenantId(tenantId);

        // Mirror Go: getById failure -> 500 INTERNAL_SERVER_ERROR, missing -> 404 NOT_FOUND.
        BoundaryHierarchy existing;
        try {
            existing = hierarchyService.getById(hierarchyId, tenantId);
        } catch (Exception e) {
            throw new BoundaryApiException(500, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                    "Failed to get hierarchy", null);
        }
        if (existing == null) {
            throw ControllerSupport.error(404, ErrorCodes.NOT_FOUND, "Hierarchy not found", "Hierarchy not found");
        }

        // Preserve the original hierarchyType (not updatable).
        request.getHierarchy().setHierarchyType(existing.getHierarchyType());

        // Mirror Go: any update failure -> 500 INTERNAL_SERVER_ERROR.
        try {
            hierarchyService.update(request, tenantId, userId, requestId);
        } catch (Exception e) {
            throw new BoundaryApiException(500, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                    "Failed to update hierarchy", null);
        }

        return new BoundaryHierarchyResponse(List.of(request.getHierarchy()));
    }

    // ----- Relationship -----

    public BoundaryRelationshipResponse createRelationship(BoundaryRelationshipRequest request, String tenantId,
                                                           String userId, String requestId) {
        request.getRelationship().setTenantId(tenantId);

        // Mirror Go: "does not exist" -> 404 NOT_FOUND, "Duplicate relationship" -> 409 CONFLICT,
        // any other failure -> 400 BAD_REQUEST.
        try {
            relationshipService.create(request, tenantId, userId, requestId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("does not exist")) {
                throw new BoundaryApiException(404, ErrorCodes.NOT_FOUND, msg, "Failed to create relationship", null);
            }
            if (msg != null && msg.contains("Duplicate relationship")) {
                throw new BoundaryApiException(409, ErrorCodes.CONFLICT, msg, "Failed to create relationship", null);
            }
            throw new BoundaryApiException(400, ErrorCodes.BAD_REQUEST, msg, "Failed to create relationship", null);
        }

        return new BoundaryRelationshipResponse(List.of(request.getRelationship()));
    }

    public BoundarySearchResponse getRelationships(HttpServletRequest http, String tenantId) {
        String hierarchyType = http.getParameter("hierarchyType");
        if (isEmpty(hierarchyType)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing hierarchyType query parameter", "Invalid request payload");
        }

        BoundaryRelationshipSearchCriteria criteria = new BoundaryRelationshipSearchCriteria();
        criteria.setTenantId(tenantId);
        criteria.setHierarchyType(hierarchyType);
        criteria.setBoundaryType(orEmpty(http.getParameter("boundaryType")));

        String[] codes = http.getParameterValues("codes");
        if (codes != null && codes.length > 0) {
            criteria.setCodes(List.of(codes));
        }
        String parent = http.getParameter("parent");
        if (notEmpty(parent)) {
            criteria.setParent(parent);
        }
        if ("true".equals(http.getParameter("includeChildren"))) {
            criteria.setIncludeChildren(true);
        }
        if ("true".equals(http.getParameter("includeParents"))) {
            criteria.setIncludeParents(true);
        }
        String latStr = http.getParameter("latitude");
        if (notEmpty(latStr)) {
            criteria.setLatitude(parseDoubleOrError(latStr, "Invalid latitude parameter"));
        }
        String lonStr = http.getParameter("longitude");
        if (notEmpty(lonStr)) {
            criteria.setLongitude(parseDoubleOrError(lonStr, "Invalid longitude parameter"));
        }
        String limitStr = http.getParameter("limit");
        if (notEmpty(limitStr)) {
            try {
                criteria.setLimit(Integer.parseInt(limitStr));
            } catch (NumberFormatException ignore) {
                // Go silently ignores parse errors for relationship limit/offset.
            }
        }
        String offsetStr = http.getParameter("offset");
        if (notEmpty(offsetStr)) {
            try {
                criteria.setOffset(Integer.parseInt(offsetStr));
            } catch (NumberFormatException ignore) {
                // Go silently ignores parse errors.
            }
        }

        return getRelationships(criteria);
    }

    public BoundarySearchResponse getRelationships(BoundaryRelationshipSearchCriteria criteria) {
        validateGeoPair(criteria.getLatitude(), criteria.getLongitude());

        // Geo search: resolve the point to the boundary codes containing it, then run the
        // normal relationship search on those codes.
        if (criteria.getLatitude() != null && criteria.getLongitude() != null) {
            BoundarySearchCriteria geoCriteria = new BoundarySearchCriteria();
            geoCriteria.setTenantId(criteria.getTenantId());
            geoCriteria.setLatitude(criteria.getLatitude());
            geoCriteria.setLongitude(criteria.getLongitude());

            List<Boundary> containing;
            try {
                containing = boundaryService.search(geoCriteria);
            } catch (Exception e) {
                throw new BoundaryApiException(400, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                        "Failed to get relationships", null);
            }

            List<String> geoCodes = new java.util.ArrayList<>();
            for (Boundary b : containing) {
                geoCodes.add(b.getCode());
            }
            // Caller-supplied codes narrow the spatial matches (intersection), never widen them.
            if (criteria.getCodes() != null && !criteria.getCodes().isEmpty()) {
                geoCodes.retainAll(criteria.getCodes());
            }

            // Point outside every boundary: same empty shape the relationship search
            // renders for no matches ("boundary": null), never an unfiltered search.
            if (geoCodes.isEmpty()) {
                HierarchyRelation empty = new HierarchyRelation();
                empty.setTenantId(criteria.getTenantId());
                empty.setHierarchyType(criteria.getHierarchyType());
                return new BoundarySearchResponse(List.of(empty));
            }
            criteria.setCodes(geoCodes);
        }

        // Mirror Go: any search failure -> 400 with code INTERNAL_SERVER_ERROR.
        try {
            return relationshipService.search(criteria);
        } catch (Exception e) {
            throw new BoundaryApiException(400, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                    "Failed to get relationships", null);
        }
    }

    public BoundaryRelationshipResponse updateRelationship(BoundaryRelationship relationship, String relationshipId,
                                                           String tenantId, String userId, String requestId) {
        relationship.setId(relationshipId);
        relationship.setTenantId(tenantId);

        // Mirror Go: getById failure -> 500 INTERNAL_SERVER_ERROR, missing -> 404 NOT_FOUND.
        BoundaryRelationship existing;
        try {
            existing = relationshipService.getById(relationshipId, tenantId);
        } catch (Exception e) {
            throw new BoundaryApiException(500, ErrorCodes.INTERNAL_SERVER_ERROR, e.getMessage(),
                    "Failed to get relationship", null);
        }
        if (existing == null) {
            throw ControllerSupport.error(404, ErrorCodes.NOT_FOUND, "Relationship not found", "Relationship not found");
        }

        // Preserve the original code (ignore any code changes from request).
        relationship.setCode(existing.getCode());

        BoundaryRelationshipRequest request = new BoundaryRelationshipRequest();
        request.setRelationship(relationship);

        // Mirror Go: "does not exist" -> 404 NOT_FOUND, any other failure -> 500 INTERNAL_SERVER_ERROR.
        try {
            relationshipService.update(request, tenantId, userId, requestId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("does not exist")) {
                throw new BoundaryApiException(404, ErrorCodes.NOT_FOUND, msg, "Relationship not found", null);
            }
            throw new BoundaryApiException(500, ErrorCodes.INTERNAL_SERVER_ERROR, msg, "Failed to update relationship", null);
        }

        return new BoundaryRelationshipResponse(List.of(relationship));
    }

    // ----- body parsing -----

    public <T> T parse(byte[] body, Class<T> type) {
        return toValue(parseTree(body), type);
    }

    /**
     * Parses the request body to a JSON tree, reproducing the body-decode errors that Go's
     * {@code ShouldBindJSON} returns from {@code encoding/json}: {@code "EOF"} for an empty body and
     * {@code "unexpected EOF"} for a truncated/incomplete document.
     */
    public JsonNode parseTree(byte[] body) {
        if (body == null || body.length == 0) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, "EOF", "Invalid request payload");
        }
        try {
            return jsonMapper.readTree(body);
        } catch (Exception e) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, decodeErrorMessage(body, e),
                    "Invalid request payload");
        }
    }

    /** Converts an already-parsed JSON tree to the target type (lenient, matching Go json.Unmarshal). */
    public <T> T toValue(JsonNode tree, Class<T> type) {
        try {
            return jsonMapper.treeToValue(tree, type);
        } catch (Exception e) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, e.getMessage(), "Invalid request payload");
        }
    }

    /**
     * Maps a JSON decode failure to Go's {@code encoding/json} wording. A document that ends while a
     * value is still open (e.g. {@code "{"}) yields Go's {@code "unexpected EOF"}; other malformed
     * input falls back to the parser message.
     */
    private static String decodeErrorMessage(byte[] body, Exception e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("end-of-input") || msg.contains("Unexpected end-of-input"))) {
            return "unexpected EOF";
        }
        return msg;
    }

    // ----- helpers -----

    /**
     * Geometry type validation done in the handler. Mirrors Go handler, which runs
     * {@code if len(Geometry) > 0}: an absent geometry is skipped, but any present value — including
     * an explicit JSON {@code null} — is validated. Go unmarshals the raw JSON into a map: a JSON
     * {@code null} unmarshals to a nil map with no error and then fails the missing-type check
     * ("Invalid geometry type"), whereas arrays/scalars fail to unmarshal ("Invalid geometry JSON").
     */
    private void validateGeometryType(JsonNode geometry) {
        if (geometry == null) {
            return; // geometry absent (Go: len(Geometry) == 0)
        }
        if (geometry.isNull()) {
            // Go: json.Unmarshal("null") -> nil map, no error -> type missing.
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, "Invalid geometry type",
                    "Allowed types: Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon, GeometryCollection");
        }
        if (!geometry.isObject()) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, "Invalid geometry JSON", "Invalid geometry JSON");
        }
        JsonNode typeNode = geometry.get("type");
        String type = typeNode != null && typeNode.isString() ? typeNode.asString() : null;
        if (type == null || !GeometryType.isValid(type)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, "Invalid geometry type",
                    "Allowed types: Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon, GeometryCollection");
        }
    }

    private int parseIntOrError(String s, String message) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, message, "Invalid request payload");
        }
    }

    private double parseDoubleOrError(String s, String message) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, message, "Invalid request payload");
        }
    }

    /**
     * Geo criteria validation shared by the legacy and canonical search paths: coordinates
     * must come as a pair and lie in WGS84 range (latitude -90..90, longitude -180..180).
     * Absent coordinates (both null) mean "not a geo search" and pass.
     */
    private static void validateGeoPair(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Both latitude and longitude must be provided", "Invalid request payload");
        }
        if (latitude < -90 || latitude > 90) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "latitude must be between -90 and 90", "Invalid request payload");
        }
        if (longitude < -180 || longitude > 180) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "longitude must be between -180 and 180", "Invalid request payload");
        }
    }

    static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
