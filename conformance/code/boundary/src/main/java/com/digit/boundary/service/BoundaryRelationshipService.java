package com.digit.boundary.service;

import com.digit.boundary.config.BoundaryProperties;
import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryRelationshipRequest;
import com.digit.boundary.model.BoundaryRelationshipSearchCriteria;
import com.digit.boundary.model.BoundarySearchResponse;
import com.digit.boundary.model.EnrichedBoundary;
import com.digit.boundary.model.HierarchyRelation;
import com.digit.boundary.pubsub.EventPublisher;
import com.digit.boundary.repository.BoundaryHierarchyRepository;
import com.digit.boundary.repository.BoundaryRelationshipRepository;
import com.digit.boundary.util.HierarchyUtil;
import com.digit.boundary.validator.BoundaryRelationshipValidator;
import org.digit.tracer.model.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Boundary relationship business logic. Mirrors Go internal/service/boundary_relationship_service_impl.go. */
@Service
public class BoundaryRelationshipService {

    private static final Logger log = LoggerFactory.getLogger(BoundaryRelationshipService.class);

    private final BoundaryRelationshipRepository repo;
    private final BoundaryHierarchyRepository hierarchyRepo;
    private final Cache cache;
    private final EventPublisher eventPublisher;
    private final BoundaryProperties props;
    private final HierarchyUtil hierarchyUtil;
    private final JsonMapper jsonMapper;

    public BoundaryRelationshipService(BoundaryRelationshipRepository repo,
                                       BoundaryHierarchyRepository hierarchyRepo,
                                       Cache cache, EventPublisher eventPublisher,
                                       BoundaryProperties props, HierarchyUtil hierarchyUtil,
                                       JsonMapper jsonMapper) {
        this.repo = repo;
        this.hierarchyRepo = hierarchyRepo;
        this.cache = cache;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.hierarchyUtil = hierarchyUtil;
        this.jsonMapper = jsonMapper;
    }

    public void create(BoundaryRelationshipRequest request, String tenantId, String userId, String requestId) {
        request.getRelationship().setTenantId(tenantId);

        BoundaryRelationshipValidator.validateRelationship(repo, hierarchyRepo, request.getRelationship());

        long epoch = System.currentTimeMillis();
        request.getRelationship().setId(UUID.randomUUID().toString());
        request.getRelationship().setRequestId(requestId);
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(userId);
        ad.setModifiedBy(userId);
        ad.setCreatedTime(epoch);
        ad.setModifiedTime(epoch);
        request.getRelationship().setAuditDetails(ad);

        repo.create(request);

        eventPublisher.publishEvent(props.getPubsub().getTopics().getCreateRelationship(), "CREATE",
                tenantId, userId, request.getRelationship(), 1);

        invalidateSearchCaches(tenantId);
    }

    public BoundarySearchResponse search(BoundaryRelationshipSearchCriteria criteria) {
        enrichSearchCriteria(criteria);

        // Build cache key (mirrors Go cacheKeyParts join).
        List<String> parts = new ArrayList<>();
        parts.add(criteria.getTenantId());
        parts.add("relationship:search");
        parts.add(criteria.getCodes() == null ? "" : String.join(",", criteria.getCodes()));
        parts.add(criteria.getHierarchyType());
        parts.add(criteria.getBoundaryType());
        parts.add(criteria.getParent());
        if (criteria.isIncludeChildren()) {
            parts.add("includeChildren");
        }
        if (criteria.isIncludeParents()) {
            parts.add("includeParents");
        }
        String cacheKey = String.join(":", parts);

        String cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                return jsonMapper.readValue(cached, BoundarySearchResponse.class);
            } catch (Exception ignore) {
                // fall through
            }
        }

        List<BoundaryRelationship> relationships = searchWithInternalParameters(criteria);
        HierarchyRelation hierarchy = buildHierarchyFromMaterializedPath(relationships, criteria);

        BoundarySearchResponse response = new BoundarySearchResponse(List.of(hierarchy));

        try {
            cache.set(cacheKey, jsonMapper.writeValueAsString(response));
        } catch (Exception ignore) {
            // best-effort
        }
        return response;
    }

    /** Sets isSearchForRootNode when only tenantId+hierarchyType are provided. Mirrors Go enrichSearchCriteria. */
    private void enrichSearchCriteria(BoundaryRelationshipSearchCriteria criteria) {
        if (notEmpty(criteria.getTenantId())
                && notEmpty(criteria.getHierarchyType())
                && isEmpty(criteria.getBoundaryType())
                && (criteria.getCodes() == null || criteria.getCodes().isEmpty())) {
            criteria.setSearchForRootNode(true);
        }
    }

    public void update(BoundaryRelationshipRequest request, String tenantId, String userId, String requestId) {
        long epoch = System.currentTimeMillis();
        request.getRelationship().setTenantId(tenantId);

        BoundaryRelationshipValidator.validateRelationshipUpdate(repo, hierarchyRepo, request.getRelationship());

        BoundaryRelationship existing = repo.getById(request.getRelationship().getId(), tenantId);
        if (existing == null) {
            throw new CustomException(ErrorCodes.NOT_FOUND, "boundary relationship with id "
                    + request.getRelationship().getId() + " does not exist");
        }

        if (request.getRelationship().getAuditDetails() == null) {
            request.getRelationship().setAuditDetails(new AuditDetails());
        }
        request.getRelationship().getAuditDetails().setCreatedBy(existing.getAuditDetails().getCreatedBy());
        request.getRelationship().getAuditDetails().setCreatedTime(existing.getAuditDetails().getCreatedTime());
        request.getRelationship().setRequestId(requestId);
        request.getRelationship().getAuditDetails().setModifiedBy(userId);
        request.getRelationship().getAuditDetails().setModifiedTime(epoch);

        repo.update(request);

        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpdateRelationship(), "UPDATE",
                tenantId, userId, request.getRelationship(), 1);

        invalidateSearchCaches(tenantId);
    }

    public BoundaryRelationship getById(String id, String tenantId) {
        return repo.getById(id, tenantId);
    }

    // ---- tree building (Java-like logic ported from Go) ----

    private HierarchyRelation buildHierarchyFromMaterializedPath(List<BoundaryRelationship> relationships,
                                                                 BoundaryRelationshipSearchCriteria criteria) {
        HierarchyRelation relation = new HierarchyRelation();
        relation.setTenantId(criteria.getTenantId());
        relation.setHierarchyType(criteria.getHierarchyType());

        // Flat list when neither includeParents nor includeChildren is set.
        if (!criteria.isIncludeParents() && !criteria.isIncludeChildren()) {
            List<EnrichedBoundary> flat = new ArrayList<>();
            for (BoundaryRelationship r : relationships) {
                flat.add(toEnriched(r));
            }
            // Go returns a nil slice (-> JSON null) when there are no boundaries, not [].
            relation.setBoundary(flat.isEmpty() ? null : flat);
            return relation;
        }

        // boundaryMap: code -> enriched (single shared instance per code).
        Map<String, EnrichedBoundary> boundaryMap = new LinkedHashMap<>();
        for (BoundaryRelationship r : relationships) {
            boundaryMap.put(r.getCode(), toEnriched(r));
        }

        // boundaryType -> list of enriched (preserving Go's slice-of-values semantics).
        Map<String, List<EnrichedBoundary>> boundaryTypeVsEnriched = new LinkedHashMap<>();
        for (BoundaryRelationship r : relationships) {
            EnrichedBoundary b = boundaryMap.get(r.getCode());
            if (b != null) {
                boundaryTypeVsEnriched.computeIfAbsent(r.getBoundaryType(), k -> new ArrayList<>()).add(b);
            }
        }

        // parent code -> list of child enriched boundaries.
        Map<String, List<EnrichedBoundary>> parentVsChildren = new LinkedHashMap<>();
        for (BoundaryRelationship r : relationships) {
            if (notEmpty(r.getParent())) {
                EnrichedBoundary child = boundaryMap.get(r.getCode());
                if (child != null) {
                    parentVsChildren.computeIfAbsent(r.getParent(), k -> new ArrayList<>()).add(child);
                }
            }
        }

        List<EnrichedBoundary> seed = getSeedBoundaryList(boundaryTypeVsEnriched, criteria);
        mergeBoundariesRecursively(seed, parentVsChildren);

        // Go returns a nil slice (-> JSON null) when the tree has no roots, not [].
        relation.setBoundary(seed.isEmpty() ? null : seed);
        return relation;
    }

    private EnrichedBoundary toEnriched(BoundaryRelationship r) {
        EnrichedBoundary b = new EnrichedBoundary();
        b.setId(r.getId());
        b.setCode(r.getCode());
        b.setBoundaryType(r.getBoundaryType());
        b.setAuditDetails(r.getAuditDetails());
        b.setParent(r.getParent());
        b.setChildren(new ArrayList<>());
        return b;
    }

    /** Seed = boundaries of the first boundary type found in hierarchy order. Mirrors Go getSeedBoundaryList. */
    private List<EnrichedBoundary> getSeedBoundaryList(Map<String, List<EnrichedBoundary>> boundaryTypeVsEnriched,
                                                       BoundaryRelationshipSearchCriteria criteria) {
        List<String> hierarchyOrder;
        try {
            hierarchyOrder = hierarchyUtil.getHierarchyOrder(criteria.getTenantId(), criteria.getHierarchyType());
        } catch (Exception e) {
            log.warn("Error getting hierarchy order, falling back to default logic error={} tenant_id={} hierarchy_type={}",
                    e.getMessage(), criteria.getTenantId(), criteria.getHierarchyType());
            // Fallback: first boundary type found.
            for (List<EnrichedBoundary> boundaries : boundaryTypeVsEnriched.values()) {
                return boundaries;
            }
            return new ArrayList<>();
        }

        for (String boundaryType : hierarchyOrder) {
            List<EnrichedBoundary> boundaries = boundaryTypeVsEnriched.get(boundaryType);
            if (boundaries != null) {
                return boundaries;
            }
        }
        return new ArrayList<>();
    }

    /** Recursively attaches children to each seed boundary. Mirrors Go mergeBoundariesRecursively. */
    private void mergeBoundariesRecursively(List<EnrichedBoundary> seedBoundaries,
                                            Map<String, List<EnrichedBoundary>> parentVsChildren) {
        if (seedBoundaries == null || seedBoundaries.isEmpty()) {
            return;
        }
        for (EnrichedBoundary boundary : seedBoundaries) {
            boundary.setChildren(new ArrayList<>());
            List<EnrichedBoundary> children = parentVsChildren.get(boundary.getCode());
            if (children != null && !children.isEmpty()) {
                boundary.getChildren().addAll(children);
                mergeBoundariesRecursively(boundary.getChildren(), parentVsChildren);
            }
        }
    }

    // ---- internal search composition (Java-like logic ported from Go) ----

    private List<BoundaryRelationship> searchWithInternalParameters(BoundaryRelationshipSearchCriteria criteria) {
        List<BoundaryRelationship> main = repo.searchWithMaterializedPath(criteria);
        List<BoundaryRelationship> parents = getParentBoundaries(main, criteria);
        List<BoundaryRelationship> children = getChildrenBoundaries(main, criteria);
        return combineBoundaries(main, parents, children);
    }

    private List<BoundaryRelationship> getParentBoundaries(List<BoundaryRelationship> boundaries,
                                                           BoundaryRelationshipSearchCriteria criteria) {
        if (!criteria.isIncludeParents() || boundaries.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Boolean> ancestorCodes = new LinkedHashMap<>();
        for (BoundaryRelationship b : boundaries) {
            if (notEmpty(b.getAncestralMaterializedPath())) {
                for (String part : b.getAncestralMaterializedPath().split("\\|", -1)) {
                    if (notEmpty(part) && !part.equals(b.getCode())) {
                        ancestorCodes.put(part, true);
                    }
                }
            }
        }
        if (ancestorCodes.isEmpty()) {
            return new ArrayList<>();
        }
        BoundaryRelationshipSearchCriteria parentCriteria = new BoundaryRelationshipSearchCriteria();
        parentCriteria.setTenantId(criteria.getTenantId());
        parentCriteria.setHierarchyType(criteria.getHierarchyType());
        parentCriteria.setCodes(new ArrayList<>(ancestorCodes.keySet()));
        return repo.searchWithMaterializedPath(parentCriteria);
    }

    private List<BoundaryRelationship> getChildrenBoundaries(List<BoundaryRelationship> boundaries,
                                                            BoundaryRelationshipSearchCriteria criteria) {
        if (!criteria.isIncludeChildren() || boundaries.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> currentBoundaryCodes = new ArrayList<>();
        for (BoundaryRelationship b : boundaries) {
            currentBoundaryCodes.add(b.getCode());
        }
        BoundaryRelationshipSearchCriteria childrenCriteria = new BoundaryRelationshipSearchCriteria();
        childrenCriteria.setTenantId(criteria.getTenantId());
        childrenCriteria.setHierarchyType(criteria.getHierarchyType());
        childrenCriteria.setCurrentBoundaryCodes(currentBoundaryCodes);
        return repo.searchWithMaterializedPath(childrenCriteria);
    }

    private List<BoundaryRelationship> combineBoundaries(List<BoundaryRelationship> main,
                                                         List<BoundaryRelationship> parents,
                                                         List<BoundaryRelationship> children) {
        Map<String, BoundaryRelationship> map = new LinkedHashMap<>();
        for (BoundaryRelationship b : main) {
            map.put(b.getCode(), b);
        }
        for (BoundaryRelationship b : parents) {
            map.put(b.getCode(), b);
        }
        for (BoundaryRelationship b : children) {
            map.put(b.getCode(), b);
        }
        return new ArrayList<>(map.values());
    }

    private void invalidateSearchCaches(String tenantId) {
        cache.deleteByPrefix(tenantId + ":relationship:search:");
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
