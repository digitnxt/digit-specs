package com.digit.boundary.web;

import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.constants.Headers;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.model.BoundaryHierarchyRequest;
import com.digit.boundary.model.BoundaryHierarchyResponse;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryRelationshipRequest;
import com.digit.boundary.model.BoundaryRelationshipResponse;
import com.digit.boundary.model.BoundaryRequest;
import com.digit.boundary.model.BoundaryResponse;
import com.digit.boundary.model.BoundarySearchResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * Legacy (specification 3.0.0) boundary, hierarchy and relationship endpoints: request metadata is
 * carried in the X-* headers (X-Tenant-Id, X-User-ID, X-Request-Id). Mirrors Go
 * internal/handlers/boundary_handler.go, including the request flow (body parse first, then header
 * checks), validation, status mapping and the array error-response shape. The per-endpoint business
 * flow lives in {@link BoundaryEndpointFlows}, shared with {@link CanonicalBoundaryController},
 * which serves the same functionality under {contextPath}/v3/{canonical-prefix} with the request
 * metadata carried in the body instead of these headers.
 */
@RestController
@RequestMapping
public class BoundaryController {

    private final BoundaryEndpointFlows flows;

    public BoundaryController(BoundaryEndpointFlows flows) {
        this.flows = flows;
    }

    // ----- Boundary -----

    @PostMapping("/v3/boundaries")
    public ResponseEntity<BoundaryResponse> create(HttpServletRequest http,
                                                   @RequestBody(required = false) byte[] body) {
        // Go binds (and validates binding:"required,min=1") during ShouldBindJSON, before headers.
        JsonNode tree = flows.parseTree(body);
        GoBinding.validateBoundaryRequest(tree);
        BoundaryRequest request = flows.toValue(tree, BoundaryRequest.class);

        String tenantId = header(http, Headers.TENANT_ID);
        String userId = header(http, Headers.USER_ID);
        String requestId = header(http, Headers.REQUEST_ID);
        requireTenantAndUser(tenantId, userId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flows.createBoundaries(request, tenantId, userId, requestId));
    }

    @GetMapping("/v3/boundaries")
    public ResponseEntity<BoundaryResponse> search(HttpServletRequest http) {
        String tenantId = header(http, Headers.TENANT_ID);
        requireTenant(tenantId);
        return ResponseEntity.ok(flows.searchBoundaries(http, tenantId));
    }

    @PutMapping("/v3/boundaries/{id}")
    public ResponseEntity<BoundaryResponse> update(HttpServletRequest http, @PathVariable("id") String boundaryId,
                                                   @RequestBody(required = false) byte[] body) {
        if (BoundaryEndpointFlows.isEmpty(boundaryId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing boundary ID in URL path", "Invalid request payload");
        }

        JsonNode tree = flows.parseTree(body);
        GoBinding.validateBoundary(tree);
        Boundary boundary = flows.toValue(tree, Boundary.class);

        String tenantId = header(http, Headers.TENANT_ID);
        String userId = header(http, Headers.USER_ID);
        String requestId = header(http, Headers.REQUEST_ID);
        requireTenantAndUser(tenantId, userId);

        return ResponseEntity.ok(flows.updateBoundary(boundary, boundaryId, tenantId, userId, requestId));
    }

    // ----- Hierarchy -----

    @PostMapping("/v3/hierarchy")
    public ResponseEntity<BoundaryHierarchyResponse> createHierarchy(HttpServletRequest http,
                                                                     @RequestBody(required = false) byte[] body) {
        BoundaryHierarchyRequest request = flows.parse(body, BoundaryHierarchyRequest.class);

        String tenantId = header(http, Headers.TENANT_ID);
        String userId = header(http, Headers.USER_ID);
        String requestId = header(http, Headers.REQUEST_ID);
        requireTenantAndUser(tenantId, userId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flows.createHierarchy(request, tenantId, userId, requestId));
    }

    @GetMapping("/v3/hierarchy")
    public ResponseEntity<BoundaryHierarchyResponse> getHierarchy(HttpServletRequest http) {
        String tenantId = header(http, Headers.TENANT_ID);
        requireTenant(tenantId);
        return ResponseEntity.ok(flows.getHierarchy(http, tenantId));
    }

    @PutMapping("/v3/hierarchy/{id}")
    public ResponseEntity<BoundaryHierarchyResponse> updateHierarchy(HttpServletRequest http,
                                                                     @PathVariable("id") String hierarchyId,
                                                                     @RequestBody(required = false) byte[] body) {
        BoundaryHierarchyRequest request = flows.parse(body, BoundaryHierarchyRequest.class);

        String tenantId = header(http, Headers.TENANT_ID);
        String userId = header(http, Headers.USER_ID);
        String requestId = header(http, Headers.REQUEST_ID);
        requireTenantAndUser(tenantId, userId);
        if (BoundaryEndpointFlows.isEmpty(hierarchyId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing hierarchy ID in path", "Invalid request payload");
        }

        return ResponseEntity.ok(flows.updateHierarchy(request, hierarchyId, tenantId, userId, requestId));
    }

    // ----- Relationship -----

    @PostMapping("/v3/relationship")
    public ResponseEntity<BoundaryRelationshipResponse> createRelationship(HttpServletRequest http,
                                                                           @RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        GoBinding.validateRelationshipRequest(tree);
        BoundaryRelationshipRequest request = flows.toValue(tree, BoundaryRelationshipRequest.class);

        String tenantId = header(http, Headers.TENANT_ID);
        String userId = header(http, Headers.USER_ID);
        String requestId = header(http, Headers.REQUEST_ID);
        requireTenantAndUser(tenantId, userId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flows.createRelationship(request, tenantId, userId, requestId));
    }

    @GetMapping("/v3/relationship")
    public ResponseEntity<BoundarySearchResponse> getRelationship(HttpServletRequest http) {
        String tenantId = header(http, Headers.TENANT_ID);
        requireTenant(tenantId);
        return ResponseEntity.ok(flows.getRelationships(http, tenantId));
    }

    @PutMapping("/v3/relationship/{id}")
    public ResponseEntity<BoundaryRelationshipResponse> updateRelationship(HttpServletRequest http,
                                                                           @PathVariable("id") String relationshipId,
                                                                           @RequestBody(required = false) byte[] body) {
        if (BoundaryEndpointFlows.isEmpty(relationshipId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing relationship ID in URL path", "Invalid request payload");
        }

        JsonNode tree = flows.parseTree(body);
        GoBinding.validateRelationship(tree);
        BoundaryRelationship relationship = flows.toValue(tree, BoundaryRelationship.class);

        String tenantId = header(http, Headers.TENANT_ID);
        String userId = header(http, Headers.USER_ID);
        String requestId = header(http, Headers.REQUEST_ID);
        requireTenantAndUser(tenantId, userId);

        return ResponseEntity.ok(flows.updateRelationship(relationship, relationshipId, tenantId, userId, requestId));
    }

    // ----- helpers -----

    private static void requireTenantAndUser(String tenantId, String userId) {
        if (BoundaryEndpointFlows.isEmpty(tenantId) || BoundaryEndpointFlows.isEmpty(userId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing X-Tenant-Id or X-User-ID header", "Invalid request payload");
        }
    }

    private static void requireTenant(String tenantId) {
        if (BoundaryEndpointFlows.isEmpty(tenantId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing X-Tenant-Id header", "Invalid request payload");
        }
    }

    private static String header(HttpServletRequest http, String name) {
        return http.getHeader(name);
    }
}
