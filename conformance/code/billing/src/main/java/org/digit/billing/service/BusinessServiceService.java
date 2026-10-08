package org.digit.billing.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.model.BusinessService;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessServiceService {

    private final BusinessServiceRepository repo;

    public BusinessServiceService(BusinessServiceRepository repo) {
        this.repo = repo;
    }

    /** Batch create, all-or-nothing (Go relied on the request tx; here @Transactional). */
    @Transactional
    public List<BusinessService> create(List<BusinessServiceRequests.Create> requests,
                                        String tenantId, String userId) {
        long now = System.currentTimeMillis();
        List<BusinessServiceRow> rows = new ArrayList<>(requests.size());
        List<String> codes = new ArrayList<>(requests.size());
        for (BusinessServiceRequests.Create request : requests) {
            rows.add(toRow(request, tenantId, userId, now));
            codes.add(request.code());
        }

        validateNoDuplicates(codes, "business service codes");

        List<String> existing = repo.getExistingCodes(tenantId, codes);
        if (!existing.isEmpty()) {
            throw new CustomException(ErrorCodes.CONFLICT, "Business service already exists",
                    "business services already exists: " + existing, null, HttpStatus.CONFLICT);
        }

        try {
            repo.create(rows);
        } catch (DataIntegrityViolationException e) {
            // create race lost against uq_business_services_tenant_code (idgen-port lesson)
            throw new CustomException(ErrorCodes.CONFLICT, "Business service already exists",
                    "created concurrently by another request", null, HttpStatus.CONFLICT);
        }
        return rows.stream().map(BusinessServiceRow::toModel).toList();
    }

    public List<BusinessService> search(BusinessServiceRequests.Filters filters, String tenantId) {
        return repo.search(filters, tenantId).stream().map(BusinessServiceRow::toModel).toList();
    }

    public Optional<BusinessService> getByCode(String code, String tenantId) {
        return repo.getByCode(code, tenantId).map(BusinessServiceRow::toModel);
    }

    @Transactional
    public BusinessService update(String code, String tenantId, String userId,
                                  BusinessServiceRequests.Update request) {
        BusinessServiceRow existing = repo.fetchForUpdate(code, tenantId)
                .orElseThrow(this::notFound);

        repo.insertAudit(existing); // previous state

        BusinessServiceRow updated = toRow(request, existing, tenantId, userId, code);
        repo.update(updated);
        return updated.toModel();
    }

    @Transactional
    public BusinessService patch(String code, String tenantId, String userId,
                                 BusinessServiceRequests.Patch patch) {
        // Go validates against an unlocked read first, then re-locks in the tx
        BusinessServiceRow current = repo.getByCode(code, tenantId).orElseThrow(this::notFound);
        validatePatchWindow(patch, current.effectiveFrom, current.effectiveTo);

        BusinessServiceRow locked = repo.fetchForUpdate(code, tenantId).orElseThrow(this::notFound);
        repo.insertAudit(locked);

        applyPatch(locked, patch);
        locked.version = locked.version + 1;
        locked.modifiedBy = userId;
        locked.modifiedTime = System.currentTimeMillis();
        repo.update(locked);
        return locked.toModel();
    }

    /** Hard delete after an audit snapshot marked inactive (Go parity). */
    @Transactional
    public void delete(String code, String tenantId, String userId) {
        BusinessServiceRow existing = repo.fetchForUpdate(code, tenantId).orElseThrow(this::notFound);

        existing.isActive = false;
        existing.modifiedBy = userId;
        existing.modifiedTime = System.currentTimeMillis();
        repo.insertAudit(existing);

        try {
            repo.deleteHard(existing.id);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCodes.DEPENDENCY_EXISTS,
                    "Cannot delete: business service is referenced by dependent records (e.g. tax heads)",
                    null, null, HttpStatus.CONFLICT);
        }
    }

    private CustomException notFound() {
        return new CustomException(ErrorCodes.NOT_FOUND, "Business service not found",
                null, null, HttpStatus.NOT_FOUND);
    }

    static void validateNoDuplicates(List<String> values, String field) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> dupes = new LinkedHashSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                dupes.add(value);
            }
        }
        if (!dupes.isEmpty()) {
            throw new CustomException(ErrorCodes.DUPLICATE_VALUES,
                    "duplicate " + field + " in request", null, List.copyOf(dupes), HttpStatus.BAD_REQUEST);
        }
    }

    /** Go's strict-inequality patch rules against existing DB values (DISCOVERY §2). */
    static void validatePatchWindow(BusinessServiceRequests.Patch patch, long existingFrom, Long existingTo) {
        validateEffectiveWindow(patch.effectiveFrom(), patch.effectiveTo(), existingFrom, existingTo);
    }

    /** Shared with tax-head patch — identical rules and error codes in Go. */
    static void validateEffectiveWindow(Long from, Long to, long existingFrom, Long existingTo) {
        if (from != null && to != null) {
            if (to <= from) {
                throw new CustomException(ErrorCodes.INVALID_EFFECTIVE_RANGE,
                        "effectiveTo must be strictly greater than effectiveFrom",
                        null, List.of("effectiveTo", "effectiveFrom"), HttpStatus.BAD_REQUEST);
            }
            return;
        }
        if (to != null && to <= existingFrom) {
            throw new CustomException(ErrorCodes.INVALID_EFFECTIVE_TO,
                    "effectiveTo must be strictly greater than existing effectiveFrom",
                    null, List.of("effectiveTo"), HttpStatus.BAD_REQUEST);
        }
        if (from != null && existingTo != null && from >= existingTo) {
            throw new CustomException(ErrorCodes.INVALID_EFFECTIVE_FROM,
                    "effectiveFrom must be strictly less than existing effectiveTo",
                    null, List.of("effectiveFrom"), HttpStatus.BAD_REQUEST);
        }
    }

    private static void applyPatch(BusinessServiceRow row, BusinessServiceRequests.Patch patch) {
        if (patch.name() != null) {
            row.name = patch.name();
        }
        if (patch.collectionMode() != null) {
            row.collectionMode = patch.collectionMode();
        }
        if (patch.allowedPaymentModes() != null) {
            row.allowedPaymentModes = patch.allowedPaymentModes();
        }
        if (patch.billExpiryDays() != null) {
            row.billExpiryDays = patch.billExpiryDays();
        }
        if (patch.minPayableAmount() != null) {
            row.minPayableAmount = patch.minPayableAmount();
        }
        if (patch.roundingRuleCode() != null) {
            row.roundingRuleCode = patch.roundingRuleCode();
        }
        if (patch.effectiveFrom() != null) {
            row.effectiveFrom = patch.effectiveFrom();
        }
        if (patch.effectiveTo() != null) {
            row.effectiveTo = patch.effectiveTo();
        }
        if (patch.isActive() != null) {
            row.isActive = patch.isActive();
        }
    }

    private static BusinessServiceRow toRow(BusinessServiceRequests.Create request,
                                            String tenantId, String userId, long now) {
        BusinessServiceRow row = new BusinessServiceRow();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.code = request.code();
        row.version = 1;
        row.name = request.name();
        row.collectionMode = request.collectionMode();
        row.allowedPaymentModes = request.allowedPaymentModes();
        row.billExpiryDays = request.billExpiryDays();
        row.partialPaymentAllowed = request.partialPaymentAllowed();
        row.minPayableAmount = request.minPayableAmount();
        row.currency = request.currency();
        row.roundingRuleCode = request.roundingRuleCode();
        row.effectiveFrom = request.effectiveFrom();
        row.effectiveTo = request.effectiveTo();
        row.isActive = request.isActive();
        row.createdBy = userId;
        row.createdTime = now;
        row.modifiedBy = userId;
        row.modifiedTime = now;
        return row;
    }

    private static BusinessServiceRow toRow(BusinessServiceRequests.Update request,
                                            BusinessServiceRow existing, String tenantId,
                                            String userId, String code) {
        long now = System.currentTimeMillis();
        BusinessServiceRow row = new BusinessServiceRow();
        row.id = existing.id;
        row.tenantId = tenantId;
        row.code = code;
        row.version = existing.version + 1;
        row.name = request.name();
        row.collectionMode = request.collectionMode();
        row.allowedPaymentModes = request.allowedPaymentModes();
        row.billExpiryDays = request.billExpiryDays();
        row.partialPaymentAllowed = request.partialPaymentAllowed();
        row.minPayableAmount = request.minPayableAmount();
        row.currency = request.currency();
        row.roundingRuleCode = request.roundingRuleCode();
        row.effectiveFrom = request.effectiveFrom();
        row.effectiveTo = request.effectiveTo();
        row.isActive = request.isActive();
        row.createdBy = existing.createdBy;
        row.createdTime = existing.createdTime;
        row.modifiedBy = userId;
        row.modifiedTime = now;
        return row;
    }
}
