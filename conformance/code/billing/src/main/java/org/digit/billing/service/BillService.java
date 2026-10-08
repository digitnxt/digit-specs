package org.digit.billing.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.digit.billing.client.IdgenClient;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.config.TenantSchema;
import org.digit.billing.entity.BillRow;
import org.digit.billing.entity.BillRow.BillAccountDetailRow;
import org.digit.billing.entity.BillRow.BillDetailRow;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.entity.DemandRow;
import org.digit.billing.entity.DemandRow.LineItemRow;
import org.digit.billing.events.BillingEventPublisher;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.BulkBillStatus;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.Json;
import org.digit.billing.repo.BillRepository;
import org.digit.billing.repo.BillRepository.BillTree;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.billing.repo.DemandRepository;
import org.digit.billing.repo.TaxHeadRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BillService {

    public static final List<DemandStatus> BILLABLE_DEMAND_STATUSES =
            List.of(DemandStatus.ACTIVE, DemandStatus.FROZEN, DemandStatus.PARTIALLY_PAID);

    private final BillRepository repo;
    private final DemandRepository demandRepo;
    private final BusinessServiceRepository bsRepo;
    private final TaxHeadRepository taxHeadRepo;
    private final IdgenClient idgen;
    private final BillingEventPublisher events;
    private final BillingProperties properties;
    private final TransactionTemplate tx;
    private final TenantSchema tenantSchema;

    public BillService(BillRepository repo, DemandRepository demandRepo, BusinessServiceRepository bsRepo,
                       TaxHeadRepository taxHeadRepo, IdgenClient idgen, BillingEventPublisher events,
                       BillingProperties properties, TransactionTemplate tx, TenantSchema tenantSchema) {
        this.repo = repo;
        this.demandRepo = demandRepo;
        this.bsRepo = bsRepo;
        this.taxHeadRepo = taxHeadRepo;
        this.idgen = idgen;
        this.events = events;
        this.properties = properties;
        this.tx = tx;
        this.tenantSchema = tenantSchema;
    }

    // ── generate (single) ─────────────────────────────────────────────────────

    public Bill generate(BillRequests.GenerateBillCriteria criteria, String tenantId, String userId) {
        BusinessServiceRow bs = validateBusinessService(criteria.businessServiceCode(), tenantId);

        BillTree result;
        try {
            result = generateTx(criteria, tenantId, userId, bs);
        } catch (DataIntegrityViolationException e) {
            // Lost the uniq_active_bill race: another request created the bill first. This used to
            // re-run generateTx once and return the winner's bill, giving the caller a silent 201.
            // That retry cannot work under the tenant-migration filter — the failed statement has
            // already aborted the request-wide transaction, so the second attempt fails on 25P02 and
            // the commit throws regardless. Reporting the conflict is the honest answer: re-issuing
            // hits the existing-ACTIVE-bill branch in generateTx and returns that bill, so the caller
            // reaches the same outcome in two calls instead of one.
            throw new CustomException(ErrorCodes.CONFLICT,
                    "A bill for this consumer and business service was created concurrently",
                    "retry the request; the existing active bill will be returned",
                    null, HttpStatus.CONFLICT);
        }

        return result.bills().get(0).toModel(result.details(), result.accountsByDetail());
    }

    private BillTree generateTx(BillRequests.GenerateBillCriteria criteria, String tenantId,
                                String userId, BusinessServiceRow bs) {
        return tx.execute(status -> {
            long now = System.currentTimeMillis();

            Optional<BillTree> existingOpt = repo.getActiveBillForUpdate(
                    tenantId, criteria.businessServiceCode(), criteria.consumerCode());

            if (existingOpt.isPresent()) {
                BillTree existing = existingOpt.get();
                BillRow bill = existing.bills().get(0);
                if (bill.billExpiryAt != null && bill.billExpiryAt < now) {
                    expireBillsWithData(existing, tenantId, userId, now);
                } else {
                    return existing; // still ACTIVE → return as-is (Go parity, 201)
                }
            }

            BillTree fresh = processSingleConsumer(criteria, tenantId, userId, now, bs.billExpiryDays);
            repo.create(fresh.bills().get(0), fresh.details(), flatten(fresh.accountsByDetail()));
            return fresh;
        });
    }

    private BillTree processSingleConsumer(BillRequests.GenerateBillCriteria criteria, String tenantId,
                                           String userId, long now, Integer bsExpiryDays) {
        List<DemandRow> demands = demandRepo.getDemandsByConsumersForUpdate(
                tenantId, criteria.businessServiceCode(), List.of(criteria.consumerCode()),
                BILLABLE_DEMAND_STATUSES);
        if (demands.isEmpty()) {
            throw new CustomException(ErrorCodes.NO_ELIGIBLE_DEMANDS, "no eligible demands found",
                    null, null, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Map<UUID, List<LineItemRow>> itemsByDemand =
                demandRepo.getLineItemsByDemandIds(demands.stream().map(d -> d.id).toList());

        String billNumber;
        try {
            billNumber = idgen.generateId(tenantId, properties.idgen().billNumberTemplate(),
                    Map.of("BSCODE", criteria.businessServiceCode()));
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCodes.GENERATION_FAILED, "Failed to generate bill",
                    "failed to generate bill number: " + e.getMessage(), null, HttpStatus.BAD_REQUEST);
        }

        Long expiry = determineBillExpiry(demands.get(0), bsExpiryDays, now);

        Map<String, Integer> orderMap = taxHeadRepo.getOrderNumbersByCodes(
                tenantId, extractTaxHeadCodes(itemsByDemand));

        BillTree tree = buildBillRows(criteria.businessServiceCode(), criteria.consumerCode(), criteria,
                demands, itemsByDemand, orderMap, tenantId, userId, billNumber, expiry, now);

        auditAndFreezeActiveDemands(demands, itemsByDemand, tenantId, userId, now);
        return tree;
    }

    // ── search / cancel ───────────────────────────────────────────────────────

    public List<Bill> search(BillRequests.Filters filters, String tenantId) {
        BillTree tree = repo.search(filters, tenantId);
        Map<UUID, List<BillDetailRow>> detailsByBill = new LinkedHashMap<>();
        for (BillDetailRow detail : tree.details()) {
            detailsByBill.computeIfAbsent(detail.billId, k -> new ArrayList<>()).add(detail);
        }
        return tree.bills().stream()
                .map(bill -> bill.toModel(detailsByBill.getOrDefault(bill.id, List.of()), tree.accountsByDetail()))
                .toList();
    }

    /** Q9: statusToBeUpdated must be CANCELLED; request metadata is merged into the bill. */
    public Bill cancel(BillRequests.UpdateBillStatus request, String tenantId, String userId) {
        if (request.statusToBeUpdated() != BillStatus.CANCELLED) {
            throw new CustomException(ErrorCodes.INVALID_STATUS,
                    "Only CANCELLED is a valid statusToBeUpdated for bill cancel",
                    null, List.of(request.statusToBeUpdated().name()), HttpStatus.BAD_REQUEST);
        }

        tx.executeWithoutResult(status -> {
            BillRow bill = repo.fetchActiveBillForUpdate(tenantId, request.businessServiceCode(),
                            request.consumerCode())
                    .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                            "No active bill found for the given consumer and business service",
                            null, null, HttpStatus.NOT_FOUND));

            repo.insertAudit(bill, List.of(), List.of());

            Map<String, Object> metadata = new LinkedHashMap<>(bill.metadata == null ? Map.of() : bill.metadata);
            metadata.putAll(request.metadata());
            repo.cancelById(bill.id, Json.writeMap(metadata), userId, System.currentTimeMillis());
        });

        // Go re-fetches via search (limit 1, newest)
        List<Bill> bills = search(new BillRequests.Filters(request.businessServiceCode(),
                List.of(request.consumerCode()), null, null, null, null, null, 1, 0), tenantId);
        if (bills.isEmpty()) {
            throw new CustomException(ErrorCodes.CANCELLATION_FAILED, "Failed to cancel bill",
                    "bill not found after update", null, HttpStatus.BAD_REQUEST);
        }
        return bills.get(0);
    }

    // ── bulk generation ───────────────────────────────────────────────────────

    public BillRequests.BulkBillResponse bulkGenerate(BillRequests.BulkBillGenerator criteria,
                                                      String tenantId, String userId) {
        validateBusinessService(criteria.businessServiceCode(), tenantId);

        String maxCode = demandRepo.getMaxConsumerCode(tenantId, criteria.businessServiceCode(),
                BILLABLE_DEMAND_STATUSES);

        int batchSize = properties.bulkBillConsumerBatchSize();
        if (batchSize <= 0) {
            throw new CustomException("BULK_GENERATION_FAILED", "Bulk bill generation failed",
                    "invalid batch size configuration", null, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String lastSeen = "";
        String requestId = UUID.randomUUID().toString();
        int totalConsumers = 0;
        int totalJobs = 0;

        while (true) {
            List<String> codes = demandRepo.getDistinctConsumerCodesBatch(tenantId,
                    criteria.businessServiceCode(), BILLABLE_DEMAND_STATUSES, lastSeen, maxCode, batchSize);
            if (codes.isEmpty()) {
                break;
            }
            BillRequests.BulkBillGenerationJob job = new BillRequests.BulkBillGenerationJob(
                    UUID.randomUUID().toString(), requestId, tenantId, criteria.businessServiceCode(),
                    codes, userId, totalJobs + 1, criteria.metadata());

            // Q7: publish failures propagate (EVENT_BUS_FAILURE) — no silent job loss
            events.publish(properties.topics().bulkBillGeneration(), "BULK_BILL_GENERATION",
                    tenantId, userId, job);

            totalConsumers += codes.size();
            totalJobs++;
            lastSeen = codes.get(codes.size() - 1);
        }

        return new BillRequests.BulkBillResponse(requestId, criteria.businessServiceCode(),
                BulkBillStatus.ACCEPTED, totalJobs, totalConsumers,
                Map.of("maxConsumerCode", maxCode));
    }

    /**
     * Consumer-side job processing (called by the pubsub handler).
     *
     * <p>Runs on a pub/sub consumer thread, which the tenant-migration filter never touches, so every
     * transaction here sets {@code search_path} from the job's tenant as its first statement — see
     * {@link TenantSchema}. That includes the opening read, which previously ran in autocommit and
     * would otherwise query {@code public} while the rest of the job wrote the tenant's schema.
     *
     * <p>The steps stay in separate transactions deliberately. Expiring a bill that is past its
     * expiry is correct independently of whether the generation that follows succeeds, and on a DLQ
     * retry the already-expired bills are simply no longer ACTIVE, so the job converges.
     */
    public void processBulkBillJob(BillRequests.BulkBillGenerationJob job) {
        String tenantId = job.tenantId();
        String userId = job.userId();
        String bsCode = job.businessServiceCode();
        List<String> consumerCodes = job.consumerCodes();

        List<BillRow> existingBills = tx.execute(status -> {
            tenantSchema.applyTo(tenantId);
            return repo.getActiveBillsByConsumers(tenantId, bsCode, consumerCodes);
        });
        long now = System.currentTimeMillis();

        if (existingBills.isEmpty()) {
            tx.executeWithoutResult(status -> {
                tenantSchema.applyTo(tenantId);
                processDemandsAndGenerateBills(consumerCodes, tenantId, userId, bsCode, now);
            });
            return;
        }

        List<UUID> expiredBillIds = new ArrayList<>();
        Set<String> activeConsumers = new HashSet<>();
        for (BillRow bill : existingBills) {
            if (bill.billExpiryAt != null && bill.billExpiryAt < now) {
                expiredBillIds.add(bill.id);
            } else {
                activeConsumers.add(bill.consumerCode);
            }
        }

        if (!expiredBillIds.isEmpty()) {
            tx.executeWithoutResult(status -> {
                tenantSchema.applyTo(tenantId);
                BillTree locked = repo.getByIdsForUpdate(tenantId, expiredBillIds);
                expireBillsWithData(locked, tenantId, userId, now);
            });
        }

        List<String> validConsumers = consumerCodes.stream()
                .filter(consumer -> !activeConsumers.contains(consumer))
                .toList();
        if (validConsumers.isEmpty()) {
            return;
        }

        tx.executeWithoutResult(status -> {
            tenantSchema.applyTo(tenantId);
            processDemandsAndGenerateBills(validConsumers, tenantId, userId, bsCode, now);
        });
    }

    private void processDemandsAndGenerateBills(List<String> consumerCodes, String tenantId,
                                                String userId, String bsCode, long now) {
        BusinessServiceRow bs = bsRepo.getByCode(bsCode, tenantId)
                .orElseThrow(() -> new IllegalStateException("business service not found: " + bsCode));

        List<DemandRow> demands = demandRepo.getDemandsByConsumersForUpdate(
                tenantId, bsCode, consumerCodes, BILLABLE_DEMAND_STATUSES);
        Map<UUID, List<LineItemRow>> itemsByDemand =
                demandRepo.getLineItemsByDemandIds(demands.stream().map(d -> d.id).toList());

        Map<String, List<DemandRow>> demandsByConsumer = new LinkedHashMap<>();
        for (DemandRow demand : demands) {
            demandsByConsumer.computeIfAbsent(demand.consumerCode, k -> new ArrayList<>()).add(demand);
        }

        Map<String, Integer> orderMap = taxHeadRepo.getOrderNumbersByCodes(
                tenantId, extractTaxHeadCodes(itemsByDemand));

        // pre-generate all bill numbers in one bulk idgen call (Go parity)
        long billableCount = consumerCodes.stream().filter(demandsByConsumer::containsKey).count();
        List<String> billNumbers = billableCount == 0 ? List.of()
                : idgen.bulkGenerateId(tenantId, properties.idgen().billNumberTemplate(),
                        (int) billableCount, Map.of("BSCODE", bsCode));
        int billNumberIdx = 0;

        List<BillRow> bills = new ArrayList<>();
        List<BillDetailRow> allDetails = new ArrayList<>();
        List<BillAccountDetailRow> allAccounts = new ArrayList<>();
        List<DemandRow> demandAudits = new ArrayList<>();
        List<LineItemRow> itemAudits = new ArrayList<>();
        List<UUID> activeDemandIds = new ArrayList<>();
        Set<UUID> seenDemands = new HashSet<>();

        for (String consumer : consumerCodes) {
            List<DemandRow> consumerDemands = demandsByConsumer.get(consumer);
            if (consumerDemands == null || consumerDemands.isEmpty()) {
                continue;
            }
            String billNumber = billNumbers.get(billNumberIdx++);
            DemandRow latest = consumerDemands.get(0); // ordered consumer_code, period_to DESC
            Long expiry = determineBillExpiry(latest, bs.billExpiryDays, now);

            BillTree tree = buildBillRows(bsCode, consumer, null, consumerDemands, itemsByDemand,
                    orderMap, tenantId, userId, billNumber, expiry, now);
            bills.addAll(tree.bills());
            allDetails.addAll(tree.details());
            allAccounts.addAll(flatten(tree.accountsByDetail()));

            for (DemandRow demand : consumerDemands) {
                if (demand.status != DemandStatus.ACTIVE || !seenDemands.add(demand.id)) {
                    continue;
                }
                demandAudits.add(demand);
                itemAudits.addAll(itemsByDemand.getOrDefault(demand.id, List.of()));
                activeDemandIds.add(demand.id);
            }
        }

        if (!demandAudits.isEmpty()) {
            demandRepo.insertAuditBulk(demandAudits, itemAudits);
        }
        demandRepo.bulkFreezeDemands(activeDemandIds, tenantId, userId, now);
        if (!bills.isEmpty()) {
            repo.bulkInsertAll(bills, allDetails, allAccounts);
        }
    }

    /**
     * Per-consumer DLQ retry: error only when ALL consumers fail (Go parity).
     *
     * <p>Each consumer runs in its own transaction, opened here rather than left to {@code generate}.
     * Two reasons, both load-bearing. It is a consumer thread, so {@code search_path} has to be set
     * explicitly and {@code SET LOCAL} only survives inside the transaction it is issued in. And the
     * per-consumer boundary is what keeps one bad consumer from taking the rest down — were these to
     * share a transaction, the first failure would abort it and every later consumer would fail on
     * "current transaction is aborted", making the "all failed" test below trivially true and looping
     * the job through the DLQ forever.
     */
    public void retryBulkJobPerConsumer(BillRequests.BulkBillGenerationJob job) {
        if (job.consumerCodes() == null || job.consumerCodes().isEmpty()) {
            return; // review finding #5: 0 == 0 would read as "all failed" and loop the DLQ
        }
        int failed = 0;
        for (String consumerCode : job.consumerCodes()) {
            try {
                tx.executeWithoutResult(status -> {
                    tenantSchema.applyTo(job.tenantId());
                    generate(new BillRequests.GenerateBillCriteria(job.businessServiceCode(), consumerCode,
                            null, null, null, null, null), job.tenantId(), job.userId());
                });
            } catch (RuntimeException e) {
                failed++;
            }
        }
        if (failed == job.consumerCodes().size()) {
            throw new IllegalStateException("all consumers failed in DLQ job: jobID=" + job.id());
        }
    }

    // ── shared pieces ─────────────────────────────────────────────────────────

    BusinessServiceRow validateBusinessService(String code, String tenantId) {
        Optional<BusinessServiceRow> bs = bsRepo.getByCode(code, tenantId);
        if (bs.isEmpty() || !bs.get().isActive) {
            throw new CustomException(ErrorCodes.INVALID_BUSINESS_SERVICE,
                    "Business service code is invalid or inactive",
                    null, List.of(code), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return bs.get();
    }

    /**
     * Builds a bill + details + account details from a consumer's demands
     * (Go buildBillDBModels). Amounts are outstanding = total - collected.
     */
    BillTree buildBillRows(String bsCode, String consumerCode, BillRequests.GenerateBillCriteria payer,
                           List<DemandRow> demands, Map<UUID, List<LineItemRow>> itemsByDemand,
                           Map<String, Integer> orderMap, String tenantId, String userId,
                           String billNumber, Long expiry, long now) {
        UUID billId = UUID.randomUUID();
        List<BillDetailRow> details = new ArrayList<>(demands.size());
        Map<UUID, List<BillAccountDetailRow>> accountsByDetail = new LinkedHashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (DemandRow demand : demands) {
            BigDecimal outstanding = demand.totalAmount.subtract(demand.totalCollectedAmount);
            totalAmount = totalAmount.add(outstanding);

            BillDetailRow detail = new BillDetailRow();
            detail.id = UUID.randomUUID();
            detail.tenantId = tenantId;
            detail.billId = billId;
            detail.demandId = demand.id;
            detail.amount = outstanding;
            detail.amountPaid = BigDecimal.ZERO;
            detail.periodFrom = demand.periodFrom;
            detail.periodTo = demand.periodTo;
            detail.createdBy = userId;
            detail.createdTime = now;
            detail.modifiedBy = userId;
            detail.modifiedTime = now;
            details.add(detail);

            List<BillAccountDetailRow> accounts = new ArrayList<>();
            for (LineItemRow item : itemsByDemand.getOrDefault(demand.id, List.of())) {
                Integer orderNumber = orderMap.get(item.taxHeadCode);
                if (orderNumber == null) {
                    throw new CustomException(ErrorCodes.GENERATION_FAILED, "Failed to generate bill",
                            "missing tax head: " + item.taxHeadCode, null, HttpStatus.BAD_REQUEST);
                }
                BillAccountDetailRow account = new BillAccountDetailRow();
                account.id = UUID.randomUUID();
                account.tenantId = tenantId;
                account.billDetailId = detail.id;
                account.lineItemId = item.id;
                account.taxHeadCode = item.taxHeadCode;
                account.orderNumber = orderNumber;
                account.amount = item.amount.subtract(item.collectedAmount);
                account.adjustedAmount = BigDecimal.ZERO;
                account.createdBy = userId;
                account.createdTime = now;
                account.modifiedBy = userId;
                account.modifiedTime = now;
                accounts.add(account);
            }
            accountsByDetail.put(detail.id, accounts);
        }

        if (totalAmount.signum() == 0) {
            throw new CustomException(ErrorCodes.GENERATION_FAILED, "Failed to generate bill",
                    "no outstanding amount found", null, HttpStatus.BAD_REQUEST);
        }

        BillRow bill = new BillRow();
        bill.id = billId;
        bill.tenantId = tenantId;
        bill.businessServiceCode = bsCode;
        bill.consumerCode = consumerCode;
        if (payer != null) {
            bill.payerId = payer.payerId();
            bill.payerName = payer.payerName();
            bill.payerAddress = payer.payerAddress();
            bill.payerMobileNumber = payer.payerMobileNumber();
            bill.payerEmail = payer.payerEmail();
        }
        bill.billNumber = billNumber;
        bill.billIssueAt = now;
        bill.billExpiryAt = expiry;
        bill.status = BillStatus.ACTIVE;
        bill.totalAmount = totalAmount;
        bill.totalCollectedAmount = BigDecimal.ZERO;
        bill.metadata = Map.of();
        bill.createdBy = userId;
        bill.createdTime = now;
        bill.modifiedBy = userId;
        bill.modifiedTime = now;

        return new BillTree(List.of(bill), details, accountsByDetail);
    }

    private void auditAndFreezeActiveDemands(List<DemandRow> demands, Map<UUID, List<LineItemRow>> itemsByDemand,
                                             String tenantId, String userId, long now) {
        List<DemandRow> demandAudits = new ArrayList<>();
        List<LineItemRow> itemAudits = new ArrayList<>();
        List<UUID> activeIds = new ArrayList<>();
        for (DemandRow demand : demands) {
            if (demand.status != DemandStatus.ACTIVE) {
                continue;
            }
            demandAudits.add(demand);
            itemAudits.addAll(itemsByDemand.getOrDefault(demand.id, List.of()));
            activeIds.add(demand.id);
        }
        if (!demandAudits.isEmpty()) {
            demandRepo.insertAuditBulk(demandAudits, itemAudits);
            demandRepo.bulkFreezeDemands(activeIds, tenantId, userId, now);
        }
    }

    /** Expire the ACTIVE bills of a locked tree, with audit bundle first (Go expireBillsWithData). */
    void expireBillsWithData(BillTree tree, String tenantId, String userId, long now) {
        List<BillRow> activeBills = tree.bills().stream()
                .filter(bill -> bill.status == BillStatus.ACTIVE)
                .toList();
        if (activeBills.isEmpty()) {
            return;
        }
        Set<UUID> activeBillIds = new LinkedHashSet<>();
        activeBills.forEach(bill -> activeBillIds.add(bill.id));

        List<BillDetailRow> activeDetails = tree.details().stream()
                .filter(detail -> activeBillIds.contains(detail.billId))
                .toList();
        Set<UUID> detailIds = new HashSet<>();
        activeDetails.forEach(detail -> detailIds.add(detail.id));

        List<BillAccountDetailRow> accounts = new ArrayList<>();
        tree.accountsByDetail().forEach((detailId, accs) -> {
            if (detailIds.contains(detailId)) {
                accounts.addAll(accs);
            }
        });

        repo.insertAuditBulk(activeBills, activeDetails, accounts);
        repo.bulkExpireBills(activeBills.stream().map(bill -> bill.id).toList(), tenantId, userId, now);
    }

    static Long determineBillExpiry(DemandRow demand, Integer bsExpiryDays, long now) {
        Integer days = demand.billExpiryDays != null ? demand.billExpiryDays : bsExpiryDays;
        if (days == null || days == 0) {
            return null;
        }
        return now + days * 86_400_000L;
    }

    private static List<String> extractTaxHeadCodes(Map<UUID, List<LineItemRow>> itemsByDemand) {
        Set<String> codes = new LinkedHashSet<>();
        for (List<LineItemRow> items : itemsByDemand.values()) {
            for (LineItemRow item : items) {
                codes.add(item.taxHeadCode);
            }
        }
        return List.copyOf(codes);
    }

    private static List<BillAccountDetailRow> flatten(Map<UUID, List<BillAccountDetailRow>> accountsByDetail) {
        List<BillAccountDetailRow> all = new ArrayList<>();
        accountsByDetail.values().forEach(all::addAll);
        return all;
    }
}
