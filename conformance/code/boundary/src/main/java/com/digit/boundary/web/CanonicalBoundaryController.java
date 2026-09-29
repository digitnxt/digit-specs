package com.digit.boundary.web;

import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.model.BoundaryHierarchyRequest;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryRelationshipRequest;
import com.digit.boundary.model.BoundaryRelationshipSearchCriteria;
import com.digit.boundary.model.BoundaryRequest;
import com.digit.boundary.model.BoundarySearchCriteria;
import com.digit.boundary.model.RequestMetadata;
import com.digit.boundary.model.ResponseMetadata;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Canonical boundary, hierarchy and relationship endpoints, mounted at
 * {contextPath}/v3/{canonical-prefix}/{apiPath} (prefix from {@code boundary.api.canonical-prefix},
 * default {@code canonical}): same functionality and business flow as the legacy
 * {@link BoundaryController}, but request metadata travels inside the request body as a top-level
 * {@code RequestMetadata} object instead of the X-* headers. The user id comes from the decoded JWT
 * claims in {@code RequestMetadata.userInfo} ({@code sub} claim) — there is no X-User-ID
 * dependency. Responses carry the same existing fields plus a top-level {@code ResponseMetadata}
 * that propagates requestId/correlationId/tenantId/msgId unchanged.
 *
 * <p>Every request body keeps the same consistent shape: {@code RequestMetadata} plus one named
 * payload object ({@code boundary}, {@code hierarchy}, {@code relationship}). The search (GET)
 * endpoints follow it too — the legacy query params (codes, hierarchyType, boundaryType, parent,
 * includeChildren, includeParents, limit, offset) move into a body object named after its criteria
 * model ({@code boundarySearchCriteria}, {@code boundaryHierarchySearchCriteria},
 * {@code boundaryRelationshipSearchCriteria}).
 *
 * <p>This controller is a thin adapter: it resolves tenantId/userId/requestId from the metadata and
 * delegates to the same {@link BoundaryEndpointFlows} the legacy controller uses. Both controllers
 * are always active; the canonical prefix keeps the endpoint mappings distinct.
 */
@RestController
@RequestMapping("/v3/${boundary.api.canonical-prefix:canonical}")
public class CanonicalBoundaryController {

    /** Top-level JSON key carrying the request metadata envelope. */
    static final String REQUEST_METADATA = "RequestMetadata";
    /** Top-level JSON key carrying the response metadata envelope. */
    static final String RESPONSE_METADATA = "ResponseMetadata";
    /**
     * Top-level JSON keys carrying the search parameters on the GET (search) endpoints — the
     * camelCase name of the bound criteria model, matching how the write endpoints name their
     * payload object after its model (boundary/hierarchy/relationship).
     */
    static final String BOUNDARY_SEARCH_CRITERIA = "boundarySearchCriteria";
    static final String HIERARCHY_SEARCH_CRITERIA = "boundaryHierarchySearchCriteria";
    static final String RELATIONSHIP_SEARCH_CRITERIA = "boundaryRelationshipSearchCriteria";

    private final BoundaryEndpointFlows flows;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    public CanonicalBoundaryController(BoundaryEndpointFlows flows, JsonMapper jsonMapper, Validator validator) {
        this.flows = flows;
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    // ----- Boundary -----

    @PostMapping("/boundaries")
    public ResponseEntity<ObjectNode> create(@RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        GoBinding.validateBoundaryRequest(tree);
        BoundaryRequest request = flows.toValue(tree, BoundaryRequest.class);

        RequestMetadata metadata = metadata(tree, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(metadata,
                flows.createBoundaries(request, metadata.getTenantId(), metadata.getUserId(),
                        metadata.getRequestId())));
    }

    @GetMapping("/boundaries")
    public ResponseEntity<ObjectNode> search(@RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        RequestMetadata metadata = metadata(tree, false);

        BoundarySearchCriteria criteria =
                criteria(tree, BOUNDARY_SEARCH_CRITERIA, BoundarySearchCriteria.class, BoundarySearchCriteria::new);
        criteria.setTenantId(metadata.getTenantId());
        boolean hasCodes = criteria.getCodes() != null && !criteria.getCodes().isEmpty();
        boolean hasGeo = criteria.getLatitude() != null || criteria.getLongitude() != null;
        if (!hasCodes && !hasGeo) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing required parameter: codes (or latitude and longitude)", "Invalid request payload");
        }

        return ResponseEntity.ok(envelope(metadata, flows.searchBoundaries(criteria)));
    }

    @PutMapping("/boundaries/{id}")
    public ResponseEntity<ObjectNode> update(@PathVariable("id") String boundaryId,
                                             @RequestBody(required = false) byte[] body) {
        if (BoundaryEndpointFlows.isEmpty(boundaryId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing boundary ID in URL path", "Invalid request payload");
        }

        JsonNode tree = flows.parseTree(body);
        JsonNode boundaryNode = tree.isObject() ? tree.get("boundary") : null;
        GoBinding.validateBoundary(boundaryNode);
        Boundary boundary = flows.toValue(boundaryNode, Boundary.class);

        RequestMetadata metadata = metadata(tree, true);
        return ResponseEntity.ok(envelope(metadata,
                flows.updateBoundary(boundary, boundaryId, metadata.getTenantId(), metadata.getUserId(),
                        metadata.getRequestId())));
    }

    // ----- Hierarchy -----

    @PostMapping("/hierarchy")
    public ResponseEntity<ObjectNode> createHierarchy(@RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        BoundaryHierarchyRequest request = flows.toValue(tree, BoundaryHierarchyRequest.class);

        RequestMetadata metadata = metadata(tree, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(metadata,
                flows.createHierarchy(request, metadata.getTenantId(), metadata.getUserId(),
                        metadata.getRequestId())));
    }

    @GetMapping("/hierarchy")
    public ResponseEntity<ObjectNode> getHierarchy(@RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        RequestMetadata metadata = metadata(tree, false);

        BoundaryHierarchySearchCriteria criteria = criteria(tree, HIERARCHY_SEARCH_CRITERIA,
                BoundaryHierarchySearchCriteria.class, BoundaryHierarchySearchCriteria::new);
        criteria.setTenantId(metadata.getTenantId());
        // hierarchyType is an optional filter: tenant-only searches return every hierarchy.
        if (criteria.getHierarchyType() == null) criteria.setHierarchyType("");

        return ResponseEntity.ok(envelope(metadata, flows.getHierarchy(criteria)));
    }

    @PutMapping("/hierarchy/{id}")
    public ResponseEntity<ObjectNode> updateHierarchy(@PathVariable("id") String hierarchyId,
                                                      @RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        BoundaryHierarchyRequest request = flows.toValue(tree, BoundaryHierarchyRequest.class);

        RequestMetadata metadata = metadata(tree, true);
        if (BoundaryEndpointFlows.isEmpty(hierarchyId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing hierarchy ID in path", "Invalid request payload");
        }

        return ResponseEntity.ok(envelope(metadata,
                flows.updateHierarchy(request, hierarchyId, metadata.getTenantId(), metadata.getUserId(),
                        metadata.getRequestId())));
    }

    // ----- Relationship -----

    @PostMapping("/relationship")
    public ResponseEntity<ObjectNode> createRelationship(@RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        GoBinding.validateRelationshipRequest(tree);
        BoundaryRelationshipRequest request = flows.toValue(tree, BoundaryRelationshipRequest.class);

        RequestMetadata metadata = metadata(tree, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(envelope(metadata,
                flows.createRelationship(request, metadata.getTenantId(), metadata.getUserId(),
                        metadata.getRequestId())));
    }

    @GetMapping("/relationship")
    public ResponseEntity<ObjectNode> getRelationship(@RequestBody(required = false) byte[] body) {
        JsonNode tree = flows.parseTree(body);
        RequestMetadata metadata = metadata(tree, false);

        BoundaryRelationshipSearchCriteria criteria = criteria(tree, RELATIONSHIP_SEARCH_CRITERIA,
                BoundaryRelationshipSearchCriteria.class, BoundaryRelationshipSearchCriteria::new);
        criteria.setTenantId(metadata.getTenantId());
        if (BoundaryEndpointFlows.isEmpty(criteria.getHierarchyType())) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing hierarchyType parameter", "Invalid request payload");
        }
        // Match legacy query-param semantics: absent filters are empty strings, and the
        // internal-only fields can never be set from the request body.
        if (criteria.getBoundaryType() == null) criteria.setBoundaryType("");
        if (criteria.getParent() == null) criteria.setParent("");
        criteria.setCurrentBoundaryCodes(null);
        criteria.setSearchForRootNode(false);

        return ResponseEntity.ok(envelope(metadata, flows.getRelationships(criteria)));
    }

    @PutMapping("/relationship/{id}")
    public ResponseEntity<ObjectNode> updateRelationship(@PathVariable("id") String relationshipId,
                                                         @RequestBody(required = false) byte[] body) {
        if (BoundaryEndpointFlows.isEmpty(relationshipId)) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing relationship ID in URL path", "Invalid request payload");
        }

        JsonNode tree = flows.parseTree(body);
        JsonNode relationshipNode = tree.isObject() ? tree.get("relationship") : null;
        GoBinding.validateRelationship(relationshipNode);
        BoundaryRelationship relationship = flows.toValue(relationshipNode, BoundaryRelationship.class);

        RequestMetadata metadata = metadata(tree, true);
        return ResponseEntity.ok(envelope(metadata,
                flows.updateRelationship(relationship, relationshipId, metadata.getTenantId(),
                        metadata.getUserId(), metadata.getRequestId())));
    }

    // ----- helpers -----

    /**
     * Extracts and validates the {@code RequestMetadata} envelope from the parsed request body —
     * the canonical-API counterpart of the legacy header checks. Bean Validation enforces the
     * contract (tenantId + ts required, ts a 13-digit epoch, 2–64 char ids); when
     * {@code userRequired} (create/update endpoints, where legacy required X-User-ID) the
     * {@code sub} claim of {@code userInfo} must yield a user id. Failures surface through the
     * standard error contract ({@link BoundaryApiException} -> array error response).
     */
    private RequestMetadata metadata(JsonNode tree, boolean userRequired) {
        JsonNode node = tree.isObject() ? tree.get(REQUEST_METADATA) : null;
        if (node == null || node.isNull()) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing RequestMetadata in request body", "Invalid request payload");
        }
        RequestMetadata metadata = flows.toValue(node, RequestMetadata.class);

        Set<ConstraintViolation<RequestMetadata>> violations = validator.validate(metadata);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> REQUEST_METADATA + "." + v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST, message, "Invalid request payload");
        }

        if (userRequired && BoundaryEndpointFlows.isEmpty(metadata.getUserId())) {
            throw ControllerSupport.error(400, ErrorCodes.BAD_REQUEST,
                    "Missing user id: RequestMetadata.userInfo.sub is required", "Invalid request payload");
        }
        return metadata;
    }

    /**
     * Binds the named search-criteria body object to the given existing criteria model. An absent
     * or null criteria object yields an empty criteria instance, so the endpoint's own
     * required-parameter checks decide what is mandatory.
     */
    private <T> T criteria(JsonNode tree, String key, Class<T> type, Supplier<T> empty) {
        JsonNode node = tree.isObject() ? tree.get(key) : null;
        if (node == null || node.isNull()) {
            return empty.get();
        }
        return flows.toValue(node, type);
    }

    /**
     * Wraps an existing response object in the metadata envelope: the unchanged response fields plus
     * a top-level {@code ResponseMetadata} propagating the request's ids.
     */
    private ObjectNode envelope(RequestMetadata metadata, Object response) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.set(RESPONSE_METADATA,
                jsonMapper.valueToTree(ResponseMetadata.from(metadata, System.currentTimeMillis())));
        if (jsonMapper.valueToTree(response) instanceof ObjectNode fields) {
            root.setAll(fields);
        }
        return root;
    }
}
