package org.digit.billing.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.entity.BillRow;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.entity.DemandRow;
import org.digit.billing.entity.DemandRow.LineItemRow;
import org.digit.billing.entity.TaxHeadRow;
import org.digit.billing.model.Amounts;
import org.digit.billing.model.Demand;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandRequests.BulkFailure;
import org.digit.billing.model.DemandRequests.BulkResponse;
import org.digit.billing.model.DemandRequests.LineItemCreate;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.Json;
import org.digit.billing.repo.BillRepository;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.billing.repo.DemandPeriodConflictException;
import org.digit.billing.repo.DemandRepository;
import org.digit.billing.repo.TaxHeadRepository;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.model.Error;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DemandService {

    private static final BigDecimal AMOUNT_MIN = BigDecimal.valueOf(-1_000_000_000L);
    private static final BigDecimal AMOUNT_MAX = BigDecimal.valueOf(1_000_000_000L);

    private final DemandRepository repo;
    private final BusinessServiceRepository bsRepo;
    private final TaxHeadRepository taxHeadRepo;
    private final BillRepository billRepo;
    private final TransactionTemplate tx;
    private final BillingProperties properties;

    public DemandService(DemandRepository repo, BusinessServiceRepository bsRepo,
                         TaxHeadRepository taxHeadRepo, BillRepository billRepo, TransactionTemplate tx,
                         BillingProperties properties) {
        this.repo = repo;
        this.bsRepo = bsRepo;
        this.taxHeadRepo = taxHeadRepo;
        this.billRepo = billRepo;
        this.tx = tx;
        this.properties = properties;
    }

    /**
     * Bulk create. Two failure classes, handled differently on purpose.
     *
     * <p><b>Validation</b> failures are a pure function of the request, so every item is checked and
     * reported individually in {@code failures} — partial success is preserved, as before.
     *
     * <p><b>Write</b> failures are fatal to the whole request. They used to be collected per item
     * too, each item having its own transaction. The tenant-migration filter now wraps the request
     * in one transaction, so the first failed statement aborts it: every later item would fail with
     * "current transaction is aborted" and be reported as a failure it never actually had, and the
     * final commit would throw after the handler had already built a success-shaped body. Worse,
     * whether the caller saw that lie or a 500 depended on whether the response had outgrown
     * Tomcat's 8KB buffer. Failing the request outright is the honest behaviour, and nothing is
     * persisted either way.
     */
    public BulkResponse create(List<DemandRequests.Create> requests, String tenantId, String userId) {
        List<Demand> success = new ArrayList<>();
        List<BulkFailure> failures = new ArrayList<>();
        Map<String, Boolean> bsCache = new HashMap<>();
        Map<String, TaxHeadRow> taxHeadCache = new HashMap<>();

        for (int idx = 0; idx < requests.size(); idx++) {
            DemandRequests.Create item = requests.get(idx);

            List<Error> errors = validateDemandCreate(item, tenantId, bsCache, taxHeadCache);
            if (!errors.isEmpty()) {
                failures.add(new BulkFailure(idx, errors));
                continue;
            }

            final int itemIdx = idx;
            try {
                Demand created = tx.execute(status -> {
                    long now = System.currentTimeMillis();
                    DemandRequests.Create effective = item;
                    if (properties.demandEnableArrears()) {
                        effective = rollForwardArrears(item, tenantId, userId, now);
                    }
                    Rows rows = toRows(effective, tenantId, userId, now);
                    repo.create(rows.demand(), rows.items());
                    return rows.demand().toModel(rows.items());
                });
                success.add(created);
            } catch (DemandPeriodConflictException e) {
                // Translated rather than propagated raw: the tracer's advice would render an
                // unrecognised exception as a 400 named after its class. This keeps the documented
                // DEMAND_CONFLICT code and names the offending item, which is what the caller needs
                // to fix the batch and resubmit.
                throw new CustomException(ErrorCodes.DEMAND_CONFLICT,
                        "Demand already exists for this period",
                        "Only one demand per period is allowed for this consumer (item " + itemIdx
                                + "); no demands were created",
                        null, HttpStatus.CONFLICT);
            }
        }
        return new BulkResponse(success, failures);
    }

    public BulkResponse update(List<DemandRequests.Update> requests, String tenantId, String userId) {
        List<Demand> success = new ArrayList<>();
        List<BulkFailure> failures = new ArrayList<>();
        Map<String, Boolean> bsCache = new HashMap<>();
        Map<String, TaxHeadRow> taxHeadCache = new HashMap<>();

        for (int idx = 0; idx < requests.size(); idx++) {
            DemandRequests.Update item = requests.get(idx);

            List<Error> errors = validateDemandCreate(item.asCreate(), tenantId, bsCache, taxHeadCache);
            if (!errors.isEmpty()) {
                failures.add(new BulkFailure(idx, errors));
                continue;
            }

            final int itemIdx = idx;
            try {
                Demand updated = tx.execute(status -> {
                    long now = System.currentTimeMillis();
                    DemandRow existing = repo.fetchForUpdate(item.id(), tenantId)
                            .orElseThrow(DemandNotFound::new);
                    List<LineItemRow> existingItems = repo.getLineItems(existing.id);

                    if (!isEditable(existing.status)) {
                        throw new CustomException(ErrorCodes.UPDATE_FAILED, "Failed to update demand",
                                "demand %s cannot be updated in status %s (item %d); no demands were updated"
                                        .formatted(existing.id, existing.status, itemIdx),
                                null, HttpStatus.CONFLICT);
                    }

                    repo.insertAudit(existing, existingItems);

                    Rows rows = toUpdatedRows(item, existing, tenantId, userId, now);
                    repo.replace(rows.demand(), rows.items());
                    return rows.demand().toModel(rows.items());
                });
                success.add(updated);
            } catch (DemandNotFound e) {
                // Fatal now, like the conflict below: see the note on create(). A missing id is
                // discovered by the SELECT ... FOR UPDATE, so it is a write-path failure even
                // though nothing was written yet.
                throw new CustomException(ErrorCodes.NOT_FOUND, "Demand not found",
                        "demand not found (item " + itemIdx + "); no demands were updated",
                        null, HttpStatus.NOT_FOUND);
            } catch (DemandPeriodConflictException e) {
                throw new CustomException(ErrorCodes.DEMAND_CONFLICT,
                        "Demand period overlap detected",
                        "Only one demand per period is allowed (item " + itemIdx
                                + "); no demands were updated",
                        null, HttpStatus.CONFLICT);
            }
        }
        return new BulkResponse(success, failures);
    }

    public Demand getById(UUID id, String tenantId) {
        DemandRow demand = repo.getById(id, tenantId).orElseThrow(DemandService::notFound);
        return demand.toModel(repo.getLineItems(demand.id));
    }

    public List<Demand> search(DemandRequests.Filters filters, String tenantId) {
        List<DemandRow> demands = repo.search(filters, tenantId);
        Map<UUID, List<LineItemRow>> items =
                repo.getLineItemsByDemandIds(demands.stream().map(d -> d.id).toList());
        return demands.stream().map(d -> d.toModel(items.getOrDefault(d.id, List.of()))).toList();
    }

    public Demand patch(UUID id, String tenantId, String userId, DemandRequests.Patch patch) {
        try {
            patchTx(id, tenantId, userId, patch);
        } catch (DemandPeriodConflictException e) {
            // review finding #2: reachable via status DRAFT→ACTIVE (or consumerCode change)
            // onto an occupied period — without this catch the exception class name leaks
            // as the wire error code via the advice's unhandled branch
            throw new CustomException(ErrorCodes.DEMAND_CONFLICT, "Demand period overlap detected",
                    "Only one demand per period is allowed", null, HttpStatus.BAD_REQUEST);
        }
        // Read AFTER commit (Go parity)
        return getById(id, tenantId);
    }

    private void patchTx(UUID id, String tenantId, String userId, DemandRequests.Patch patch) {
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            DemandRow existing = repo.fetchForUpdate(id, tenantId).orElseThrow(DemandService::notFound);
            List<LineItemRow> existingItems = repo.getLineItems(existing.id);

            if (!isEditable(existing.status)) {
                throw new CustomException(ErrorCodes.INVALID_STATUS_TRANSITION,
                        "demand cannot be patched in status " + existing.status,
                        null, List.of(existing.status.name()), HttpStatus.BAD_REQUEST);
            }

            validatePatchLineItems(existing, patch, tenantId);

            repo.insertAudit(existing, existingItems);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("version", existing.version + 1);
            updates.put("modifiedBy", userId);
            updates.put("modifiedTime", now);
            if (patch.consumerCode() != null) {
                updates.put("consumer_code", patch.consumerCode());
            }
            if (patch.payer() != null) {
                updates.put("payer", Json.writeArray(patch.payer()));
            }
            if (patch.status() != null) {
                updates.put("status", patch.status());
            }

            if (patch.lineItems() != null) {
                repo.deleteLineItems(existing.id);
                BigDecimal totalAmount = BigDecimal.ZERO;
                BigDecimal totalCollected = BigDecimal.ZERO;
                List<LineItemRow> newItems = new ArrayList<>(patch.lineItems().size());
                for (LineItemCreate li : patch.lineItems()) {
                    totalAmount = totalAmount.add(li.amount());
                    totalCollected = totalCollected.add(li.collectedAmount());
                    newItems.add(toItemRow(li, existing.id, tenantId,
                            existing.createdBy, existing.createdTime, userId, now));
                }
                repo.insertLineItems(newItems);
                updates.put("total_amount", totalAmount);
                updates.put("total_collected_amount", totalCollected);
                updates.put("is_demand_paid", Amounts.eq(totalAmount, totalCollected));
            }

            repo.updateFields(existing.id, tenantId, updates);
        });
    }

    public Demand freeze(UUID id, String tenantId, String userId) {
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            DemandRow existing = repo.fetchForUpdate(id, tenantId).orElseThrow(DemandService::notFound);
            List<LineItemRow> items = repo.getLineItems(existing.id);

            if (existing.status != DemandStatus.ACTIVE) {
                throw new CustomException(ErrorCodes.FREEZE_FAILED, "Failed to freeze demand",
                        "demand %s can only be frozen from ACTIVE state (current: %s)".formatted(id, existing.status),
                        null, HttpStatus.CONFLICT);
            }

            repo.insertAudit(existing, items);
            repo.updateFields(id, tenantId, Map.of(
                    "status", DemandStatus.FROZEN,
                    "version", existing.version + 1,
                    "modifiedBy", userId,
                    "modifiedTime", now));
        });
        return getById(id, tenantId);
    }

    public Demand cancel(UUID id, String tenantId, String userId, DemandRequests.CancelRequest request) {
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            DemandRow existing = repo.fetchForUpdate(id, tenantId).orElseThrow(DemandService::notFound);
            List<LineItemRow> items = repo.getLineItems(existing.id);

            if (existing.status != DemandStatus.DRAFT && existing.status != DemandStatus.ACTIVE) {
                throw new CustomException(ErrorCodes.CANCEL_FAILED, "Failed to cancel demand",
                        "demand %s can only be cancelled from DRAFT or ACTIVE state (current: %s)"
                                .formatted(id, existing.status),
                        null, HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Map<String, Object> metadata = new LinkedHashMap<>(existing.metadata == null ? Map.of() : existing.metadata);
            if (request != null && request.reasonCode() != null) {
                metadata.put("cancellation_reason_code", request.reasonCode());
            }
            if (request != null && request.note() != null) {
                metadata.put("cancellation_note", request.note());
            }

            repo.insertAudit(existing, items);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("status", DemandStatus.CANCELLED);
            updates.put("version", existing.version + 1);
            updates.put("modifiedBy", userId);
            updates.put("modifiedTime", now);
            updates.put("metadata", Json.writeMap(metadata));
            repo.updateFields(id, tenantId, updates);
        });
        return getById(id, tenantId);
    }

    // ── validation (Go validateDemandCreate, 1:1) ─────────────────────────────

    List<Error> validateDemandCreate(DemandRequests.Create demand, String tenantId,
                                     Map<String, Boolean> bsCache, Map<String, TaxHeadRow> taxHeadCache) {
        List<Error> errors = new ArrayList<>();

        if (demand.periodTo() < demand.periodFrom()) {
            errors.add(error(ErrorCodes.INVALID_PERIOD, "periodTo must be on or after periodFrom"));
        }
        if (demand.lineItems() == null || demand.lineItems().isEmpty()) {
            errors.add(error(ErrorCodes.INVALID_LINE_ITEMS, "At least one line item is required"));
        }

        if (!Boolean.TRUE.equals(bsCache.get(demand.businessServiceCode()))) {
            Optional<BusinessServiceRow> bs = bsRepo.getByCode(demand.businessServiceCode(), tenantId);
            if (bs.isEmpty() || !bs.get().isActive) {
                errors.add(error(ErrorCodes.UNKNOWN_BUSINESS_SERVICE,
                        "Business service %s not found or inactive".formatted(demand.businessServiceCode())));
            } else {
                bsCache.put(demand.businessServiceCode(), true);
            }
        }

        Set<String> seen = new HashSet<>();
        List<LineItemCreate> items = demand.lineItems() == null ? List.of() : demand.lineItems();
        for (int idx = 0; idx < items.size(); idx++) {
            LineItemCreate item = items.get(idx);
            if (item.amount().compareTo(AMOUNT_MIN) < 0 || item.amount().compareTo(AMOUNT_MAX) > 0) {
                errors.add(error(ErrorCodes.INVALID_AMOUNT, "Line item %d amount out of range".formatted(idx)));
            }
            if (item.collectedAmount().compareTo(AMOUNT_MIN) < 0
                    || item.collectedAmount().compareTo(AMOUNT_MAX) > 0) {
                errors.add(error(ErrorCodes.INVALID_COLLECTION,
                        "Line item %d collectedAmount out of range".formatted(idx)));
            }
            if (item.amount().signum() < 0) {
                if (!Amounts.isZero(item.collectedAmount()) && !Amounts.eq(item.collectedAmount(), item.amount())) {
                    errors.add(error(ErrorCodes.INVALID_COLLECTION,
                            "Line item %d collectedAmount must be 0 or equal to amount when amount is negative"
                                    .formatted(idx)));
                }
            } else if (item.collectedAmount().signum() < 0
                    || Amounts.gt(item.collectedAmount(), item.amount())) {
                errors.add(error(ErrorCodes.INVALID_COLLECTION,
                        "Line item %d collectedAmount must be between 0 and amount".formatted(idx)));
            }
            if (!seen.add(item.taxHeadCode())) {
                errors.add(error(ErrorCodes.DUPLICATE_TAX_HEAD,
                        "Line item %d duplicates taxHeadCode %s".formatted(idx, item.taxHeadCode())));
            }

            TaxHeadRow cached = taxHeadCache.get(item.taxHeadCode());
            if (cached != null) {
                if (!cached.businessServiceCode.equals(demand.businessServiceCode())) {
                    errors.add(error(ErrorCodes.INVALID_TAX_HEAD,
                            "taxHeadCode %s does not belong to business service %s"
                                    .formatted(item.taxHeadCode(), demand.businessServiceCode())));
                }
                continue;
            }
            Optional<TaxHeadRow> taxHead = taxHeadRepo.getByCode(item.taxHeadCode(), tenantId);
            if (taxHead.isEmpty() || !taxHead.get().isActive) {
                errors.add(error(ErrorCodes.UNKNOWN_TAX_HEAD,
                        "taxHeadCode %s not found or inactive".formatted(item.taxHeadCode())));
                continue;
            }
            if (!taxHead.get().businessServiceCode.equals(demand.businessServiceCode())) {
                errors.add(error(ErrorCodes.INVALID_TAX_HEAD,
                        "taxHeadCode %s does not belong to business service %s"
                                .formatted(item.taxHeadCode(), demand.businessServiceCode())));
            }
            taxHeadCache.put(item.taxHeadCode(), taxHead.get());
        }

        return errors;
    }

    /** Q2 fix: patch line-item validation surfaces the real code at 400 (Go returned 500). */
    private void validatePatchLineItems(DemandRow existing, DemandRequests.Patch patch, String tenantId) {
        if (patch.lineItems() == null) {
            return;
        }
        DemandRequests.Create asCreate = new DemandRequests.Create(existing.businessServiceCode,
                existing.periodFrom, existing.periodTo, existing.consumerCode, null, null,
                patch.lineItems(), null, null, null);
        List<Error> errors = validateDemandCreate(asCreate, tenantId, new HashMap<>(), new HashMap<>());
        if (!errors.isEmpty()) {
            Error first = errors.get(0);
            throw new CustomException(first.getCode(), first.getMessage(), first.getDescription(),
                    null, HttpStatus.BAD_REQUEST);
        }
    }

    // ── arrears (config-gated; DISCOVERY §2) ──────────────────────────────────

    private DemandRequests.Create rollForwardArrears(DemandRequests.Create item, String tenantId,
                                                     String userId, long now) {
        Optional<DemandRow> latestOpt = repo.getLatestOpenDemandForUpdate(
                tenantId, item.businessServiceCode(), item.consumerCode());
        if (latestOpt.isEmpty()) {
            return item;
        }
        DemandRow latest = latestOpt.get();
        BigDecimal outstanding = latest.totalAmount.subtract(latest.totalCollectedAmount);
        if (outstanding.signum() <= 0) {
            return item;
        }

        String arrearCode = item.businessServiceCode() + "_ARREAR";
        Optional<TaxHeadRow> arrearHead = taxHeadRepo.getByCode(arrearCode, tenantId);
        if (arrearHead.isEmpty() || !arrearHead.get().isActive) {
            // CustomException rather than IllegalStateException: this escapes the request now that
            // write failures are fatal, and the tracer's advice renders an unrecognised exception as
            // a 400 named after its class. The arrear tax head is server-side configuration, so the
            // request is well-formed but unprocessable.
            throw new CustomException(ErrorCodes.CREATION_FAILED, "Failed to create demand",
                    "arrear tax head %s not found or inactive".formatted(arrearCode),
                    null, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        List<LineItemCreate> newItems = new ArrayList<>(item.lineItems().size() + 1);
        newItems.add(new LineItemCreate(arrearCode, outstanding, BigDecimal.ZERO, null));
        newItems.addAll(item.lineItems());

        List<String> chain = new ArrayList<>();
        chain.add(latest.id.toString());
        if (latest.arrearDemandIds != null) {
            chain.addAll(latest.arrearDemandIds);
        }

        // mark previous demand ROLL_FORWARDED (audit first)
        repo.insertAudit(latest, repo.getLineItems(latest.id));
        repo.updateFields(latest.id, tenantId, Map.of(
                "status", DemandStatus.ROLL_FORWARDED,
                "version", latest.version + 1,
                "modifiedBy", userId,
                "modifiedTime", now));

        // `latest`'s outstanding is now snapshotted into the arrear line item above; any
        // bill still asking for that same amount would let it be paid twice. Only
        // FROZEN/PARTIALLY_PAID demands have been billed at all (ACTIVE never has), and
        // billing bundles every open demand for a consumer+service atomically at generate
        // time, so the single active bill here (uniq_active_bill: at most one) is
        // guaranteed to be the one `latest` belongs to, never an unrelated demand's bill.
        if (latest.status == DemandStatus.FROZEN || latest.status == DemandStatus.PARTIALLY_PAID) {
            billRepo.fetchActiveBillForUpdate(tenantId, item.businessServiceCode(), item.consumerCode())
                    .ifPresent(bill -> cancelBillForRollForward(bill, latest.id, userId, now));
        }

        return item.withArrears(newItems, chain);
    }

    private void cancelBillForRollForward(BillRow bill, UUID rolledFromDemandId, String userId, long now) {
        billRepo.insertAudit(bill, List.of(), List.of());
        Map<String, Object> metadata = new LinkedHashMap<>(bill.metadata == null ? Map.of() : bill.metadata);
        metadata.put("cancellation_reason_code", "ROLLED_FORWARD");
        metadata.put("cancellation_note",
                "Demand " + rolledFromDemandId + " outstanding rolled forward to a new demand");
        billRepo.cancelById(bill.id, Json.writeMap(metadata), userId, now);
    }

    // ── row building (Go ToDBModels) ──────────────────────────────────────────

    record Rows(DemandRow demand, List<LineItemRow> items) {
    }

    private static Rows toRows(DemandRequests.Create create, String tenantId, String userId, long now) {
        UUID demandId = UUID.randomUUID();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCollected = BigDecimal.ZERO;
        List<LineItemRow> items = new ArrayList<>(create.lineItems().size());
        for (LineItemCreate li : create.lineItems()) {
            totalAmount = totalAmount.add(li.amount());
            totalCollected = totalCollected.add(li.collectedAmount());
            items.add(toItemRow(li, demandId, tenantId, userId, now, userId, now));
        }

        DemandRow demand = new DemandRow();
        demand.id = demandId;
        demand.tenantId = tenantId;
        demand.businessServiceCode = create.businessServiceCode();
        demand.periodFrom = create.periodFrom();
        demand.periodTo = create.periodTo();
        demand.consumerCode = create.consumerCode();
        demand.billExpiryDays = create.billExpiryDays();
        demand.payer = create.payer();
        demand.arrearDemandIds = create.arrearDemandIds();
        demand.status = create.status();
        demand.totalAmount = totalAmount;
        demand.totalCollectedAmount = totalCollected;
        demand.isDemandPaid = Amounts.eq(totalCollected, totalAmount);
        demand.metadata = create.metadata();
        demand.version = 1;
        demand.createdBy = userId;
        demand.createdTime = now;
        demand.modifiedBy = userId;
        demand.modifiedTime = now;
        return new Rows(demand, items);
    }

    private static Rows toUpdatedRows(DemandRequests.Update update, DemandRow existing,
                                      String tenantId, String userId, long now) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCollected = BigDecimal.ZERO;
        List<LineItemRow> items = new ArrayList<>(update.lineItems().size());
        for (LineItemCreate li : update.lineItems()) {
            totalAmount = totalAmount.add(li.amount());
            totalCollected = totalCollected.add(li.collectedAmount());
            // Go: new UUIDs; createdBy/Time inherited from the existing demand row
            items.add(toItemRow(li, existing.id, tenantId, existing.createdBy, existing.createdTime, userId, now));
        }

        DemandRow demand = new DemandRow();
        demand.id = existing.id;
        demand.tenantId = tenantId;
        demand.businessServiceCode = update.businessServiceCode();
        demand.periodFrom = update.periodFrom();
        demand.periodTo = update.periodTo();
        demand.consumerCode = update.consumerCode();
        demand.billExpiryDays = update.billExpiryDays();
        demand.payer = update.payer();
        demand.arrearDemandIds = update.arrearDemandIds();
        demand.status = update.status();
        demand.totalAmount = totalAmount;
        demand.totalCollectedAmount = totalCollected;
        demand.isDemandPaid = Amounts.eq(totalCollected, totalAmount);
        demand.metadata = update.metadata();
        demand.version = existing.version + 1;
        demand.createdBy = existing.createdBy;
        demand.createdTime = existing.createdTime;
        demand.modifiedBy = userId;
        demand.modifiedTime = now;
        return new Rows(demand, items);
    }

    private static LineItemRow toItemRow(LineItemCreate li, UUID demandId, String tenantId,
                                         String createdBy, long createdTime, String modifiedBy, long now) {
        LineItemRow item = new LineItemRow();
        item.id = UUID.randomUUID();
        item.tenantId = tenantId;
        item.demandId = demandId;
        item.taxHeadCode = li.taxHeadCode();
        item.amount = li.amount();
        item.collectedAmount = li.collectedAmount();
        item.metadata = li.metadata();
        item.createdBy = createdBy;
        item.createdTime = createdTime;
        item.modifiedBy = modifiedBy;
        item.modifiedTime = now;
        return item;
    }

    static boolean isEditable(DemandStatus status) {
        return status == DemandStatus.DRAFT || status == DemandStatus.ACTIVE;
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCodes.NOT_FOUND, "Demand not found",
                "Demand not found", null, HttpStatus.NOT_FOUND);
    }

    private static Error error(String code, String message) {
        return new Error(code, message, message, null);
    }

    private static final class DemandNotFound extends RuntimeException {
    }
}
