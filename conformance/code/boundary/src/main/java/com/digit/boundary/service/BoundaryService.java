package com.digit.boundary.service;

import com.digit.boundary.config.BoundaryProperties;
import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.model.BoundaryRequest;
import com.digit.boundary.model.BoundarySearchCriteria;
import com.digit.boundary.observability.BusinessMetrics;
import com.digit.boundary.pubsub.EventPublisher;
import com.digit.boundary.repository.BoundaryRepository;
import com.digit.boundary.validator.BoundaryValidator;
import org.digit.tracer.model.CustomException;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Boundary business logic. Mirrors Go internal/service/boundary_service_impl.go. */
@Service
public class BoundaryService {

    private final BoundaryRepository repo;
    private final Cache cache;
    private final EventPublisher eventPublisher;
    private final BoundaryProperties props;
    private final JsonMapper jsonMapper;
    private final BusinessMetrics businessMetrics;

    public BoundaryService(BoundaryRepository repo, Cache cache, EventPublisher eventPublisher,
                           BoundaryProperties props, JsonMapper jsonMapper, BusinessMetrics businessMetrics) {
        this.repo = repo;
        this.cache = cache;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.jsonMapper = jsonMapper;
        this.businessMetrics = businessMetrics;
    }

    public void create(BoundaryRequest request, String tenantId, String userId, String requestId) {
        long epoch = System.currentTimeMillis();
        List<String> createdCodes = new ArrayList<>();

        // Validate all boundaries first (mirrors Go ordering).
        for (Boundary b : request.getBoundary()) {
            b.setTenantId(tenantId);
            BoundaryValidator.validateBoundary(repo, b);
        }

        for (Boundary b : request.getBoundary()) {
            b.setId(UUID.randomUUID().toString());
            b.setRequestId(requestId);
            AuditDetails ad = new AuditDetails();
            ad.setCreatedBy(userId);
            ad.setModifiedBy(userId);
            ad.setCreatedTime(epoch);
            ad.setModifiedTime(epoch);
            b.setAuditDetails(ad);
            createdCodes.add(b.getCode());
        }

        repo.create(request);

        eventPublisher.publishEvent(props.getPubsub().getTopics().getCreateBoundary(), "CREATE",
                tenantId, userId, request.getBoundary(), request.getBoundary().size());

        invalidateSearchCaches(tenantId, createdCodes);
        businessMetrics.recordBoundaryCreated(tenantId, request.getBoundary().size());
    }

    public List<Boundary> search(BoundarySearchCriteria criteria) {
        // Geo searches bypass the cache: the coordinate space is continuous (keys would
        // almost never repeat) and code-based invalidation cannot tell which cached
        // points a new or changed polygon affects.
        if (criteria.getLatitude() != null && criteria.getLongitude() != null) {
            List<Boundary> result = repo.search(criteria);
            businessMetrics.recordBoundarySearched(criteria.getTenantId(), result.size());
            return result;
        }

        String cacheKey = criteria.getTenantId() + ":boundary:search:" + String.join(",", criteria.getCodes());

        String cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                List<Boundary> result = jsonMapper.readValue(cached, new TypeReference<List<Boundary>>() {});
                businessMetrics.recordBoundarySearched(criteria.getTenantId(), result.size());
                return result;
            } catch (Exception ignore) {
                // fall through to repository on parse failure (matches Go behavior)
            }
        }

        List<Boundary> result = repo.search(criteria);

        try {
            cache.set(cacheKey, jsonMapper.writeValueAsString(result));
        } catch (Exception ignore) {
            // caching is best-effort
        }

        businessMetrics.recordBoundarySearched(criteria.getTenantId(), result.size());
        return result;
    }

    public void update(BoundaryRequest request, String tenantId, String userId, String requestId) {
        long epoch = System.currentTimeMillis();
        List<String> updatedCodes = new ArrayList<>();

        for (Boundary b : request.getBoundary()) {
            b.setTenantId(tenantId);

            Boundary existing = repo.getById(b.getId(), tenantId);
            if (existing == null) {
                throw new CustomException(ErrorCodes.NOT_FOUND, "boundary with id " + b.getId() + " does not exist");
            }

            if (b.getAuditDetails() == null) {
                b.setAuditDetails(new AuditDetails());
            }
            // Preserve createdBy/createdTime from DB.
            b.getAuditDetails().setCreatedBy(existing.getAuditDetails().getCreatedBy());
            b.getAuditDetails().setCreatedTime(existing.getAuditDetails().getCreatedTime());

            b.setRequestId(requestId);
            b.getAuditDetails().setModifiedBy(userId);
            b.getAuditDetails().setModifiedTime(epoch);
            updatedCodes.add(b.getCode());
        }

        repo.update(request);

        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpdateBoundary(), "UPDATE",
                tenantId, userId, request.getBoundary(), request.getBoundary().size());

        invalidateSearchCaches(tenantId, updatedCodes);
        businessMetrics.recordBoundaryUpdated(tenantId, request.getBoundary().size());
    }

    /**
     * Invalidates search caches that might contain the given boundary codes, mirroring Go
     * {@code invalidateSearchCaches}: on Redis (pattern-capable) it deletes only the keys matching
     * each affected code ({@code <tenant>:boundary:search:*<code>*}); on the in-memory cache it falls
     * back to clearing all search caches for the tenant via prefix deletion.
     */
    private void invalidateSearchCaches(String tenantId, List<String> boundaryCodes) {
        if (cache instanceof RedisCache redis) {
            for (String code : boundaryCodes) {
                redis.deletePattern(tenantId + ":boundary:search:*" + code + "*");
            }
            return;
        }
        cache.deleteByPrefix(tenantId + ":boundary:search:");
    }
}
