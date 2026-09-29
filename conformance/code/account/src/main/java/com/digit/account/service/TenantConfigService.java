package com.digit.account.service;

import com.digit.account.config.AccountProperties;
import com.digit.account.enrichment.Enrichment;
import com.digit.account.model.Mappers;
import com.digit.account.model.TenantConfigCreateRequest;
import com.digit.account.model.TenantConfigEntity;
import com.digit.account.model.TenantConfigListResponse;
import com.digit.account.model.TenantConfigResponse;
import com.digit.account.model.TenantConfigUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.digit.account.validator.TenantConfigValidator;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v3 tenant configuration (per-tenant key/value store). Mirrors Go
 * internal/service/tenant_config_service.go.
 */
@Service
public class TenantConfigService {

    private final TenantConfigRepository configRepo;
    private final TenantRepository tenantRepo;
    private final EventPublisher eventPublisher;
    private final AccountProperties props;

    public TenantConfigService(TenantConfigRepository configRepo, TenantRepository tenantRepo,
                               EventPublisher eventPublisher, AccountProperties props) {
        this.configRepo = configRepo;
        this.tenantRepo = tenantRepo;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    /** Mirrors Create. {@code tenantCode} is the X-Tenant-Id header. */
    public TenantConfigResponse create(TenantConfigCreateRequest req, String tenantCode,
                                       String clientId, String requestId) {
        TenantConfigEntity entity = Mappers.tenantConfigCreateRequestToEntity(req, tenantCode);
        Enrichment.enrichTenantConfigEntity(entity, clientId, requestId);

        List<String> errs = TenantConfigValidator.validateTenantConfigEntity(entity);
        if (!errs.isEmpty()) {
            throw new CustomException("VALIDATION_ERROR", String.join("; ", errs));
        }

        // Referential-integrity: tenant must exist.
        if (tenantRepo != null) {
            if (tenantRepo.getByCode(entity.getTenantId()) == null) {
                throw new CustomException("TENANT_NOT_FOUND",
                        "X-Tenant-Id does not match any existing tenant", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        // Fast-path duplicate check.
        if (configRepo.getByKey(entity.getTenantId(), entity.getConfigKey()) != null) {
            throw new CustomException("DUPLICATE_RECORD",
                    "A configuration with this configKey already exists for the tenant",
                    HttpStatus.CONFLICT);
        }

        // A unique-constraint violation surfaces as a DUPLICATE_RECORD CustomException (PgErrors).
        configRepo.create(entity);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("tenantConfigId", entity.getId());
        eventData.put("tenantId", entity.getTenantId());
        eventData.put("configKey", entity.getConfigKey());
        eventPublisher.publishEvent(props.getPubsub().getTopics().getCreateTenantConfig(), "CREATE",
                entity.getTenantId(), clientId, eventData, 1);

        return Mappers.tenantConfigFromEntity(entity);
    }

    /** Mirrors Get — returns null when not found. */
    public TenantConfigResponse get(String id) {
        return Mappers.tenantConfigFromEntity(configRepo.getById(id));
    }

    /** Mirrors List. {@code tenantCode} is the X-Tenant-Id header. */
    public TenantConfigListResponse list(String tenantCode, String configKey, Boolean isActive,
                                         int page, int size) {
        TenantConfigRepository.Page p = configRepo.list(tenantCode, configKey, isActive, page, size);
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        TenantConfigListResponse resp = new TenantConfigListResponse();
        resp.setTotalCount((int) p.total);
        resp.setPage(page);
        resp.setSize(size);
        resp.setHasMore((long) page * size < p.total);
        resp.setConfigs(Mappers.tenantConfigsFromEntities(p.rows));
        return resp;
    }

    /** Mirrors Update. */
    public TenantConfigResponse update(String id, TenantConfigUpdateRequest req, String clientId,
                                       String requestId) {
        TenantConfigEntity existing = configRepo.getById(id);
        if (existing == null) {
            throw new CustomException("NOT_FOUND", "TenantConfig not found", HttpStatus.NOT_FOUND);
        }

        TenantConfigEntity updated = Mappers.tenantConfigUpdateRequestToEntity(existing, req, clientId,
                requestId, System.currentTimeMillis());

        // Validation runs before the duplicate probe so the comparison below can dereference the
        // merged key freely. Omitted fields retain the existing row's NOT NULL values, so the key is
        // already non-null; an explicitly blank one is rejected here rather than at that comparison.
        // Validating first also spares a getByKey round-trip for a request that cannot succeed.
        List<String> errs = TenantConfigValidator.validateTenantConfigEntity(updated);
        if (!errs.isEmpty()) {
            throw new CustomException("VALIDATION_ERROR", String.join("; ", errs));
        }

        if (!updated.getConfigKey().equals(existing.getConfigKey())) {
            TenantConfigEntity dup = configRepo.getByKey(existing.getTenantId(), updated.getConfigKey());
            if (dup != null && !dup.getId().equals(existing.getId())) {
                throw new CustomException("DUPLICATE_RECORD",
                        "A configuration with this configKey already exists for the tenant",
                        HttpStatus.CONFLICT);
            }
        }

        // A unique-constraint violation surfaces as a DUPLICATE_RECORD CustomException (PgErrors).
        configRepo.update(updated);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("tenantConfigId", updated.getId());
        eventData.put("tenantId", updated.getTenantId());
        eventData.put("configKey", updated.getConfigKey());
        eventData.put("isActive", updated.isActive());
        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpdateTenantConfig(), "UPDATE",
                updated.getTenantId(), clientId, eventData, 1);

        return Mappers.tenantConfigFromEntity(updated);
    }
}
