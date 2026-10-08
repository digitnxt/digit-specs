package org.digit.billing.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.entity.TaxHeadRow;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.TaxHead;
import org.digit.billing.model.TaxHeadRequests;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.billing.repo.TaxHeadRepository;
import org.digit.billing.repo.TaxHeadRepository.OrderConflict;
import org.digit.billing.repo.TaxHeadRepository.OrderPair;
import org.digit.tracer.model.CustomException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaxHeadService {

    private final TaxHeadRepository repo;
    private final BusinessServiceRepository bsRepo;

    public TaxHeadService(TaxHeadRepository repo, BusinessServiceRepository bsRepo) {
        this.repo = repo;
        this.bsRepo = bsRepo;
    }

    @Transactional
    public List<TaxHead> create(List<TaxHeadRequests.Create> requests, String tenantId, String userId) {
        long now = System.currentTimeMillis();
        List<TaxHeadRow> rows = new ArrayList<>(requests.size());
        List<String> codes = new ArrayList<>(requests.size());
        Set<String> businessServiceCodes = new LinkedHashSet<>();
        for (TaxHeadRequests.Create request : requests) {
            TaxHeadRow row = toRow(request, tenantId, userId, now);
            rows.add(row);
            codes.add(row.code);
            businessServiceCodes.add(row.businessServiceCode);
        }

        BusinessServiceService.validateNoDuplicates(codes, "tax head codes");
        ensureOrderUniquenessInRequest(rows);
        ensureActiveBusinessServices(tenantId, List.copyOf(businessServiceCodes));

        List<String> existing = repo.getExistingCodes(tenantId, codes);
        if (!existing.isEmpty()) {
            throw new CustomException(ErrorCodes.CONFLICT, "Tax head already exists",
                    "tax heads already exist: " + existing, null, HttpStatus.CONFLICT);
        }

        ensureOrderUniquenessAgainstExisting(tenantId, rows);

        try {
            repo.create(rows);
        } catch (DataIntegrityViolationException e) {
            // race lost against uq_tax_heads_tenant_code / uq_tax_heads_service_order
            throw new CustomException(ErrorCodes.CONFLICT, "Tax head already exists",
                    "created concurrently by another request", null, HttpStatus.CONFLICT);
        }
        return rows.stream().map(TaxHeadRow::toModel).toList();
    }

    public List<TaxHead> search(TaxHeadRequests.Filters filters, String tenantId) {
        return repo.search(filters, tenantId).stream().map(TaxHeadRow::toModel).toList();
    }

    public Optional<TaxHead> getByCode(String code, String tenantId) {
        return repo.getByCode(code, tenantId).map(TaxHeadRow::toModel);
    }

    @Transactional
    public TaxHead update(String code, String tenantId, String userId, TaxHeadRequests.Update request) {
        ensureActiveBusinessServices(tenantId, List.of(request.businessServiceCode()));

        TaxHeadRow existing = repo.fetchForUpdate(code, tenantId).orElseThrow(this::notFound);

        if (repo.existsByBusinessServiceAndOrderExcludingId(tenantId, request.businessServiceCode(),
                request.orderNumber(), existing.id)) {
            throw new CustomException(ErrorCodes.DUPLICATE_ORDER,
                    "order number %d already exists for business service %s"
                            .formatted(request.orderNumber(), request.businessServiceCode()),
                    null, null, HttpStatus.CONFLICT);
        }

        repo.insertAudit(existing);

        long now = System.currentTimeMillis();
        TaxHeadRow updated = new TaxHeadRow();
        updated.id = existing.id;
        updated.tenantId = tenantId;
        updated.code = code;
        updated.version = existing.version + 1;
        updated.name = request.name();
        updated.businessServiceCode = request.businessServiceCode();
        updated.category = request.category();
        updated.orderNumber = request.orderNumber();
        updated.effectiveFrom = request.effectiveFrom();
        updated.effectiveTo = request.effectiveTo();
        updated.isActive = request.isActive();
        updated.createdBy = existing.createdBy;
        updated.createdTime = existing.createdTime;
        updated.modifiedBy = userId;
        updated.modifiedTime = now;
        repo.update(updated);
        return updated.toModel();
    }

    @Transactional
    public TaxHead patch(String code, String tenantId, String userId, TaxHeadRequests.Patch patch) {
        TaxHeadRow current = repo.getByCode(code, tenantId).orElseThrow(this::notFound);
        validatePatch(patch, current, tenantId);

        TaxHeadRow locked = repo.fetchForUpdate(code, tenantId).orElseThrow(this::notFound);
        repo.insertAudit(locked);

        if (patch.name() != null) {
            locked.name = patch.name();
        }
        if (patch.businessServiceCode() != null) {
            locked.businessServiceCode = patch.businessServiceCode();
        }
        if (patch.effectiveFrom() != null) {
            locked.effectiveFrom = patch.effectiveFrom();
        }
        if (patch.effectiveTo() != null) {
            locked.effectiveTo = patch.effectiveTo();
        }
        if (patch.isActive() != null) {
            locked.isActive = patch.isActive();
        }
        locked.version = locked.version + 1;
        locked.modifiedBy = userId;
        locked.modifiedTime = System.currentTimeMillis();
        repo.update(locked);
        return locked.toModel();
    }

    @Transactional
    public void delete(String code, String tenantId, String userId) {
        TaxHeadRow existing = repo.fetchForUpdate(code, tenantId).orElseThrow(this::notFound);

        existing.isActive = false;
        existing.modifiedBy = userId;
        existing.modifiedTime = System.currentTimeMillis();
        repo.insertAudit(existing);

        try {
            repo.deleteHard(existing.id);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCodes.DEPENDENCY_EXISTS,
                    "Cannot delete: tax head is referenced by dependent records (e.g. demand line items)",
                    null, null, HttpStatus.CONFLICT);
        }
    }

    private CustomException notFound() {
        return new CustomException(ErrorCodes.NOT_FOUND, "Tax head not found",
                null, null, HttpStatus.NOT_FOUND);
    }

    private void validatePatch(TaxHeadRequests.Patch patch, TaxHeadRow existing, String tenantId) {
        BusinessServiceService.validateEffectiveWindow(patch.effectiveFrom(), patch.effectiveTo(),
                existing.effectiveFrom, existing.effectiveTo);
        if (patch.businessServiceCode() != null) {
            ensureActiveBusinessServices(tenantId, List.of(patch.businessServiceCode()));
        }
    }

    /** All referenced business services must exist AND be active → 422 (Go parity). */
    private void ensureActiveBusinessServices(String tenantId, List<String> codes) {
        List<BusinessServiceRow> services = bsRepo.getActiveByCodes(tenantId, codes);
        if (services.size() == codes.size()) {
            return;
        }
        Set<String> found = new HashSet<>();
        for (BusinessServiceRow service : services) {
            found.add(service.code);
        }
        List<String> invalid = codes.stream().filter(code -> !found.contains(code)).toList();
        throw new CustomException(ErrorCodes.INVALID_BUSINESS_SERVICE,
                "Business service codes are invalid or inactive",
                null, invalid, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private void ensureOrderUniquenessInRequest(List<TaxHeadRow> rows) {
        Map<String, Map<Integer, String>> orderMap = new HashMap<>();
        for (TaxHeadRow row : rows) {
            Map<Integer, String> orders = orderMap.computeIfAbsent(row.businessServiceCode, k -> new HashMap<>());
            String existingCode = orders.putIfAbsent(row.orderNumber, row.code);
            if (existingCode != null) {
                throw new CustomException(ErrorCodes.DUPLICATE_ORDER,
                        "duplicate order number %d for business service %s: tax heads %s and %s"
                                .formatted(row.orderNumber, row.businessServiceCode, existingCode, row.code),
                        null, null, HttpStatus.CONFLICT);
            }
        }
    }

    private void ensureOrderUniquenessAgainstExisting(String tenantId, List<TaxHeadRow> rows) {
        Set<OrderPair> pairs = new LinkedHashSet<>();
        for (TaxHeadRow row : rows) {
            pairs.add(new OrderPair(row.businessServiceCode, row.orderNumber));
        }
        List<OrderConflict> conflicts = repo.getByBusinessServiceAndOrderPairs(tenantId, List.copyOf(pairs));
        if (conflicts.isEmpty()) {
            return;
        }
        List<String> messages = new ArrayList<>();
        for (OrderConflict conflict : conflicts) {
            messages.add("order number %d already exists for business service %s in tax head %s"
                    .formatted(conflict.orderNumber(), conflict.businessServiceCode(), conflict.code()));
        }
        throw new CustomException(ErrorCodes.DUPLICATE_ORDER, String.join("; ", messages),
                null, null, HttpStatus.CONFLICT);
    }

    private static TaxHeadRow toRow(TaxHeadRequests.Create request, String tenantId, String userId, long now) {
        TaxHeadRow row = new TaxHeadRow();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.code = request.code();
        row.version = 1;
        row.name = request.name();
        row.businessServiceCode = request.businessServiceCode();
        row.category = request.category();
        row.orderNumber = request.orderNumber();
        row.effectiveFrom = request.effectiveFrom();
        row.effectiveTo = request.effectiveTo();
        row.isActive = request.isActive();
        row.createdBy = userId;
        row.createdTime = now;
        row.modifiedBy = userId;
        row.modifiedTime = now;
        return row;
    }
}
