package com.digit.boundary.service;

import com.digit.boundary.config.BoundaryProperties;
import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryHierarchyRequest;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.pubsub.EventPublisher;
import com.digit.boundary.repository.BoundaryHierarchyRepository;
import com.digit.boundary.validator.BoundaryHierarchyValidator;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/** Boundary hierarchy business logic. Mirrors Go internal/service/boundary_hierarchy_service_impl.go. */
@Service
public class BoundaryHierarchyService {

    private final BoundaryHierarchyRepository repo;
    private final Cache cache;
    private final EventPublisher eventPublisher;
    private final BoundaryProperties props;
    private final JsonMapper jsonMapper;

    public BoundaryHierarchyService(BoundaryHierarchyRepository repo, Cache cache, EventPublisher eventPublisher,
                                    BoundaryProperties props, JsonMapper jsonMapper) {
        this.repo = repo;
        this.cache = cache;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.jsonMapper = jsonMapper;
    }

    public void create(BoundaryHierarchyRequest request, String tenantId, String userId, String requestId) {
        long epoch = System.currentTimeMillis();
        request.getHierarchy().setTenantId(tenantId);

        BoundaryHierarchyValidator.validateHierarchy(repo, request.getHierarchy());

        request.getHierarchy().setId(UUID.randomUUID().toString());
        request.getHierarchy().setRequestId(requestId);
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(userId);
        ad.setModifiedBy(userId);
        ad.setCreatedTime(epoch);
        ad.setModifiedTime(epoch);
        request.getHierarchy().setAuditDetails(ad);

        repo.create(request);

        eventPublisher.publishEvent(props.getPubsub().getTopics().getCreateHierarchy(), "CREATE",
                tenantId, userId, request.getHierarchy(), 1);

        // Invalidate all hierarchy caches for the tenant so cached searches (including the
        // tenant-wide search, keyed with an empty hierarchyType) pick up the new hierarchy.
        cache.deleteByPrefix(tenantId + ":hierarchy:search:");
    }

    public List<BoundaryHierarchy> search(BoundaryHierarchySearchCriteria criteria) {
        String cacheKey = criteria.getTenantId() + ":hierarchy:search:" + criteria.getHierarchyType();

        String cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                return jsonMapper.readValue(cached, new TypeReference<List<BoundaryHierarchy>>() {});
            } catch (Exception ignore) {
                // fall through
            }
        }

        List<BoundaryHierarchy> result = repo.search(criteria);

        try {
            cache.set(cacheKey, jsonMapper.writeValueAsString(result));
        } catch (Exception ignore) {
            // best-effort
        }
        return result;
    }

    public void update(BoundaryHierarchyRequest request, String tenantId, String userId, String requestId) {
        long epoch = System.currentTimeMillis();
        request.getHierarchy().setTenantId(tenantId);

        // For updates we only validate structure (not duplicate hierarchy type).
        BoundaryHierarchyValidator.validateHierarchyStructure(request.getHierarchy().getBoundaryHierarchy());

        if (request.getHierarchy().getAuditDetails() == null) {
            request.getHierarchy().setAuditDetails(new AuditDetails());
        }
        request.getHierarchy().setRequestId(requestId);
        request.getHierarchy().getAuditDetails().setModifiedBy(userId);
        request.getHierarchy().getAuditDetails().setModifiedTime(epoch);

        repo.update(request);

        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpdateHierarchy(), "UPDATE",
                tenantId, userId, request.getHierarchy(), 1);

        // Invalidate this hierarchy type's cache and all hierarchy caches for the tenant.
        cache.delete(tenantId + ":hierarchy:search:" + request.getHierarchy().getHierarchyType());
        cache.deleteByPrefix(tenantId + ":hierarchy:search:");
    }

    public BoundaryHierarchy getById(String id, String tenantId) {
        return repo.getById(id, tenantId);
    }
}
