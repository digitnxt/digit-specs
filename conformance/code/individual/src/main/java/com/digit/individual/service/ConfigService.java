package com.digit.individual.service;

import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Config;
import com.digit.individual.model.RequestContext;
import com.digit.individual.observability.BusinessMetrics;
import com.digit.individual.pubsub.EventPublisher;
import com.digit.individual.repository.ConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists per-tenant validation config. Validation is owned by the controller; upsert assumes
 * validated input.
 */
@Service
public class ConfigService {

    private final ConfigRepository repo;
    private final EventPublisher eventPublisher;
    private final IndividualProperties props;
    private final BusinessMetrics businessMetrics;

    public ConfigService(ConfigRepository repo, EventPublisher eventPublisher, IndividualProperties props,
                         BusinessMetrics businessMetrics) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.businessMetrics = businessMetrics;
    }

    /** Result of an upsert: the persisted config and whether it was an insert. */
    public record UpsertResult(Config config, boolean created) {}

    @Transactional
    public UpsertResult upsert(RequestContext reqCtx, Config body) {
        body.setTenantId(reqCtx.getTenantId());

        // Lock the tenant's config row for the rest of this transaction so a concurrent upsert can't
        // read the same version and clobber this one — keeps the version counter honest.
        // Last-write-wins on content is intended. DB failures propagate to the tracer 500 handler.
        Config existing = repo.getByTenantForUpdate(reqCtx.getTenantId());

        long now = System.currentTimeMillis();
        boolean created = existing == null;

        if (created) {
            body.setCreatedBy(reqCtx.getUserId());
            body.setModifiedBy(reqCtx.getUserId());
            body.setCreatedTime(now);
            body.setModifiedTime(now);
            body.setVersion(1);
            body.setRequestId(reqCtx.getRequestId());
            repo.insert(body);
        } else {
            body.setId(existing.getId());
            body.setCreatedBy(existing.getCreatedBy());
            body.setCreatedTime(existing.getCreatedTime());
            body.setModifiedBy(reqCtx.getUserId());
            body.setModifiedTime(now);
            body.setVersion(existing.getVersion() + 1);
            body.setRequestId(reqCtx.getRequestId());
            repo.update(body);
        }

        businessMetrics.recordConfigUpserted(reqCtx.getTenantId(), created);
        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpsertConfig(),
                props.getPubsub().getTopics().getUpsertConfig(),
                reqCtx.getTenantId(), reqCtx.getUserId(), body, body.getVersion());
        return new UpsertResult(body, created);
    }

    public Config getByTenant(String tenantId) {
        return repo.getByTenant(tenantId);
    }
}
