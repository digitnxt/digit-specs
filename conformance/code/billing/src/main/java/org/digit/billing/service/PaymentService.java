package org.digit.billing.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.digit.billing.client.ApportionClient;
import org.digit.billing.client.IdgenClient;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.entity.BillRow;
import org.digit.billing.entity.BillRow.BillAccountDetailRow;
import org.digit.billing.entity.BillRow.BillDetailRow;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.entity.DemandRow;
import org.digit.billing.entity.DemandRow.LineItemRow;
import org.digit.billing.entity.PaymentRow;
import org.digit.billing.entity.PaymentRow.PaymentDetailRow;
import org.digit.billing.model.Amounts;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.Payment;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentRequests;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.model.ReceiptType;
import org.digit.billing.repo.BillRepository;
import org.digit.billing.repo.BillRepository.BillTree;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.billing.repo.DemandRepository;
import org.digit.billing.repo.PaymentRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    /** Codes that map to 422 (Q4 made Go's dormant paymentErrorStatus mapping live). */
    private static final Set<String> UNPROCESSABLE_CODES = Set.of(
            ErrorCodes.INVALID_BILL_ID, ErrorCodes.BILL_ALREADY_PAID,
            ErrorCodes.BILL_NOT_ACTIVE, ErrorCodes.INVALID_BUSINESS_SERVICE);

    /** Instrument statuses that block a new payment on the same bill. */
    private static final Set<InstrumentStatus> OPEN_INSTRUMENT_STATUSES = Set.of(
            InstrumentStatus.APPROVED, InstrumentStatus.APPROVAL_PENDING, InstrumentStatus.REMITTED);

    private final PaymentRepository repo;
    private final BillRepository billRepo;
    private final DemandRepository demandRepo;
    private final BusinessServiceRepository bsRepo;
    private final IdgenClient idgen;
    private final ApportionClient apportion;
    private final BillingProperties properties;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository repo, BillRepository billRepo, DemandRepository demandRepo,
                          BusinessServiceRepository bsRepo, IdgenClient idgen, ApportionClient apportion,
                          BillingProperties properties, TransactionTemplate tx) {
        this.repo = repo;
        this.billRepo = billRepo;
        this.demandRepo = demandRepo;
        this.bsRepo = bsRepo;
        this.idgen = idgen;
        this.apportion = apportion;
        this.properties = properties;
        this.tx = tx;
    }

    // ── create / validate ─────────────────────────────────────────────────────

    public Payment create(PaymentRequests.Create request, String tenantId, String userId) {
        validatePaymentCreate(request);

        return tx.execute(status -> {
            long now = System.currentTimeMillis();

            List<UUID> billIds = collectBillIds(request.paymentDetails());
            Map<UUID, Bill> billMap = fetchBills(tenantId, billIds, true);
            validateNoDuplicatePayment(tenantId, billIds);

            Payment payment = buildPaymentAggregate(request, billMap, tenantId, userId, now, false);

            List<Bill> bills = payment.paymentDetails.stream()
                    .map(pd -> pd.bill)
                    .filter(Objects::nonNull)
                    .toList();
            List<Bill> apportioned = apportion.apportionBills(tenantId, userId, bills);
            applyApportionResults(payment, apportioned);

            PaymentRow paymentRow = toPaymentRow(payment, tenantId);
            List<PaymentDetailRow> detailRows = toDetailRows(payment, tenantId);
            repo.create(paymentRow, detailRows);
            repo.insertAudit(paymentRow, detailRows);

            applyBillUpdates(payment, tenantId, userId, now);
            applyDemandUpdates(payment, tenantId, userId, now);

            return payment;
        });
    }

    /** Dry run: no locks, no idgen, no persistence, no mutation persisted (Go parity). */
    public Payment validate(PaymentRequests.Create request, String tenantId, String userId) {
        validatePaymentCreate(request);
        long now = System.currentTimeMillis();

        List<UUID> billIds = collectBillIds(request.paymentDetails());
        Map<UUID, Bill> billMap = fetchBills(tenantId, billIds, false);
        validateNoDuplicatePayment(tenantId, billIds);

        return buildPaymentAggregate(request, billMap, tenantId, userId, now, true);
    }

    public List<Payment> search(PaymentRequests.Filters filters, String tenantId) {
        List<PaymentRow> payments = repo.search(filters, tenantId);
        List<PaymentDetailRow> details = repo.getDetailsByPaymentIds(
                payments.stream().map(p -> p.id).toList(), tenantId);
        Map<UUID, List<PaymentDetailRow>> byPayment = new LinkedHashMap<>();
        for (PaymentDetailRow detail : details) {
            byPayment.computeIfAbsent(detail.paymentId, k -> new ArrayList<>()).add(detail);
        }
        return payments.stream()
                .map(payment -> payment.toModel(byPayment.getOrDefault(payment.id, List.of())))
                .toList();
    }

    public Payment getById(UUID id, String tenantId) {
        PaymentRow payment = repo.getById(id, tenantId)
                .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND, "Payment not found",
                        null, null, HttpStatus.NOT_FOUND));
        return payment.toModel(repo.getDetails(payment.id, tenantId));
    }

    // ── aggregate building (Go buildPaymentAggregate) ─────────────────────────

    Payment buildPaymentAggregate(PaymentRequests.Create request, Map<UUID, Bill> billMap,
                                  String tenantId, String userId, long now, boolean isValidate) {
        Map<String, String> errMap = new LinkedHashMap<>();

        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.totalAmountPaid = request.totalAmountPaid();
        payment.paidBy = request.paidBy();
        payment.paymentMode = request.paymentMode();
        payment.paymentStatus = determinePaymentStatus(request.paymentMode());
        payment.instrumentStatus = determineInstrumentStatus(request.paymentMode());
        payment.instrumentNumber = request.instrumentNumber();
        payment.instrumentDate = request.instrumentDate();
        payment.ifscCode = request.ifscCode();
        payment.payerId = request.payerId();
        payment.payerName = request.payerName();
        payment.payerAddress = request.payerAddress();
        payment.payerMobileNumber = request.payerMobileNumber();
        payment.payerEmail = request.payerEmail();
        payment.fileStoreId = request.fileStoreId();
        payment.metadata = request.metadata();
        payment.auditDetail = new AuditDetail(userId, now, userId, now);

        payment.transactionDate = request.transactionDate() != null ? request.transactionDate() : now;

        // instrument date defaults to txn date for instant modes
        if (payment.instrumentDate == null
                && (request.paymentMode() == PaymentMode.CASH || request.paymentMode().isOnlineFamily())) {
            payment.instrumentDate = payment.transactionDate;
        }

        if (!isValidate && request.paymentMode() == PaymentMode.CASH && request.transactionNumber() == null) {
            try {
                payment.transactionNumber = idgen.generateId(tenantId,
                        properties.idgen().transactionNumberTemplate(), Map.of("TENANTID", tenantId));
            } catch (RuntimeException e) {
                throw new CustomException(ErrorCodes.TXN_NUMBER_GENERATION_ERROR,
                        "Failed to generate transaction number", e.getMessage(), null,
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else if (request.transactionNumber() != null) {
            payment.transactionNumber = request.transactionNumber();
        }

        // pre-generate receipt numbers per distinct BSCODE (Go parity)
        Map<String, List<String>> receiptQueues = new HashMap<>();
        Map<String, Integer> receiptIndexes = new HashMap<>();
        if (!isValidate) {
            Map<String, Integer> countsByBsCode = new LinkedHashMap<>();
            for (PaymentRequests.DetailCreate detail : request.paymentDetails()) {
                Bill bill = billMap.get(detail.billId());
                if (bill != null) {
                    countsByBsCode.merge(bill.businessServiceCode, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : countsByBsCode.entrySet()) {
                try {
                    receiptQueues.put(entry.getKey(), idgen.bulkGenerateId(tenantId,
                            properties.idgen().receiptNumberTemplate(), entry.getValue(),
                            Map.of("BSCODE", entry.getKey())));
                } catch (RuntimeException e) {
                    throw new CustomException(ErrorCodes.RECEIPT_NUMBER_GENERATION_ERROR,
                            "Failed to generate receipt numbers", e.getMessage(), null,
                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        BigDecimal totalDue = BigDecimal.ZERO;
        for (PaymentRequests.DetailCreate detailCreate : request.paymentDetails()) {
            Bill bill = billMap.get(detailCreate.billId());
            if (bill == null) {
                errMap.put(ErrorCodes.INVALID_BILL_ID, "Bill ID %s not found".formatted(detailCreate.billId()));
                continue;
            }
            if (!validateDetailAgainstBill(request.paymentMode(), detailCreate, bill, tenantId, errMap)) {
                continue;
            }

            String receiptNumber = "";
            if (!isValidate) {
                List<String> queue = receiptQueues.get(bill.businessServiceCode);
                int index = receiptIndexes.merge(bill.businessServiceCode, 1, Integer::sum) - 1;
                receiptNumber = queue.get(index);
            }

            bill.totalCollectedAmount = bill.totalCollectedAmount.add(detailCreate.totalAmountPaid());

            Payment.PaymentDetail detail = new Payment.PaymentDetail();
            detail.totalAmountDue = bill.totalAmount;
            detail.totalAmountPaid = detailCreate.totalAmountPaid();
            detail.manualReceiptNumber = detailCreate.manualReceiptNumber();
            detail.manualReceiptDate = detailCreate.manualReceiptDate();
            detail.receiptNumber = receiptNumber;
            detail.receiptDate = now;
            detail.receiptType = ReceiptType.BILLBASED;
            detail.businessServiceCode = bill.businessServiceCode;
            detail.billId = bill.id;
            detail.bill = bill;
            detail.metadata = detailCreate.metadata();
            payment.paymentDetails.add(detail);

            totalDue = totalDue.add(bill.totalAmount);
        }

        if (!errMap.isEmpty()) {
            throw paymentValidationError(errMap);
        }
        payment.totalAmountDue = totalDue;
        return payment;
    }

    /** Q4: one array element per failing code, 422 when any code is unprocessable. */
    private static CustomException paymentValidationError(Map<String, String> errMap) {
        boolean unprocessable = errMap.keySet().stream().anyMatch(UNPROCESSABLE_CODES::contains);
        return new CustomException(errMap,
                unprocessable ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST);
    }

    // ── pre-DB validation ─────────────────────────────────────────────────────

    void validatePaymentCreate(PaymentRequests.Create request) {
        Map<String, String> errMap = new LinkedHashMap<>();
        validateInstrument(request, errMap);
        if (!errMap.isEmpty()) {
            throw paymentValidationError(errMap);
        }

        // Q10: root total must equal the detail sum (Go never reconciled these)
        BigDecimal detailSum = request.paymentDetails().stream()
                .map(PaymentRequests.DetailCreate::totalAmountPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!Amounts.eq(request.totalAmountPaid(), detailSum)) {
            throw new CustomException(ErrorCodes.INVALID_TOTAL_AMOUNT_PAID,
                    "totalAmountPaid must equal the sum of paymentDetails totalAmountPaid",
                    "totalAmountPaid=%s, sum(details)=%s".formatted(
                            request.totalAmountPaid().toPlainString(), detailSum.toPlainString()),
                    null, HttpStatus.BAD_REQUEST);
        }
    }

    void validateInstrument(PaymentRequests.Create request, Map<String, String> errMap) {
        switch (request.paymentMode()) {
            case CHEQUE, DD -> validateChequeOrDd(request, errMap);
            case OFFLINE_NEFT, OFFLINE_RTGS, POSTAL_ORDER -> validateOfflineTransfer(request, errMap);
            case ONLINE, CARD, UPI, NETBANKING, WALLET, ONLINE_NEFT, ONLINE_RTGS ->
                    validateOnlinePayment(request, errMap);
            case CASH -> { /* no instrument requirements; txn number may be system-generated */ }
        }
    }

    private void validateChequeOrDd(PaymentRequests.Create request, Map<String, String> errMap) {
        if (isBlank(request.instrumentNumber())) {
            errMap.put(ErrorCodes.INVALID_INST_NUMBER, "Instrument number is mandatory for cheque/DD");
        }
        if (request.instrumentDate() == null) {
            errMap.put(ErrorCodes.INVALID_INST_DATE, "Instrument date is mandatory for cheque/DD");
            return;
        }
        long instrument = request.instrumentDate();
        int maxDays = properties.maxInstrumentDateAgeDays();
        if (request.transactionDate() != null) {
            long txn = request.transactionDate();
            if (instrument > txn) {
                errMap.put(ErrorCodes.INVALID_CHEQUE_DD_DATE, "Instrument date cannot be after receipt date");
            }
            if ((txn - instrument) / 86_400_000L > maxDays) {
                errMap.put(ErrorCodes.CHEQUE_DD_DATE_EXCEEDS_MANUAL_RECEIPT,
                        "Instrument date exceeds allowed days from manual receipt date");
            }
        } else {
            long now = System.currentTimeMillis();
            if ((now - instrument) / 86_400_000L > maxDays) {
                errMap.put(ErrorCodes.CHEQUE_DD_DATE_EXCEEDS_RECEIPT,
                        "Instrument date exceeds allowed days from receipt date");
            }
            if (instrument > now) {
                // Q5: the constant Go defined but never used
                errMap.put(ErrorCodes.CHEQUE_DD_DATE_IN_FUTURE, "Instrument date cannot be in the future");
            }
        }
    }

    private void validateOfflineTransfer(PaymentRequests.Create request, Map<String, String> errMap) {
        if (isBlank(request.instrumentNumber())) {
            errMap.put(ErrorCodes.INVALID_INST_NUMBER,
                    "Instrument number is mandatory for offline transfer(NEFT/RTGS/POSTAL ORDER)");
        }
        if (request.instrumentDate() == null) {
            errMap.put(ErrorCodes.INVALID_INST_DATE,
                    "Instrument date is mandatory for offline transfer(NEFT/RTGS/POSTAL ORDER)");
            return;
        }
        if (request.instrumentDate() > System.currentTimeMillis()) {
            errMap.put(ErrorCodes.INVALID_NEFT_RTGS_DATE,
                    "NEFT/RTGS date should not be greater than Manual Receipt Date");
        }
    }

    private void validateOnlinePayment(PaymentRequests.Create request, Map<String, String> errMap) {
        if (isBlank(request.transactionNumber())) {
            errMap.put(ErrorCodes.INVALID_TXN_NUMBER, "Transaction number is mandatory for online payment");
        }
        if (isBlank(request.instrumentNumber())) {
            errMap.put(ErrorCodes.INVALID_INSTRUMENT_NUMBER, "Instrument number is mandatory for online payment");
        }
    }

    boolean validateDetailAgainstBill(PaymentMode mode, PaymentRequests.DetailCreate detail, Bill bill,
                                      String tenantId, Map<String, String> errMap) {
        String billId = bill.id.toString();

        Optional<BusinessServiceRow> bsOpt = bsRepo.getByCode(bill.businessServiceCode, tenantId);
        if (bsOpt.isEmpty()) {
            errMap.put(ErrorCodes.INVALID_BUSINESS_SERVICE,
                    "Failed to get business service: " + bill.businessServiceCode);
            return false;
        }
        BusinessServiceRow bs = bsOpt.get();
        BigDecimal paid = detail.totalAmountPaid();

        if (paid.signum() < 0) {
            errMap.put(ErrorCodes.INVALID_PAYMENTDETAIL,
                    "Amount paid cannot be negative for bill: " + billId);
            return false;
        }
        if (bs.minPayableAmount != null && Amounts.lt(paid, bs.minPayableAmount)) {
            errMap.put(ErrorCodes.INVALID_PAYMENTDETAIL,
                    "Amount paid cannot be less than minimum amount for bill: " + billId);
            return false;
        }
        if (!bs.partialPaymentAllowed && Amounts.lt(paid, bill.totalAmount)) {
            errMap.put(ErrorCodes.INVALID_PAYMENTDETAIL, "Partial payment not allowed for bill: " + billId);
            return false;
        }
        // The mirror of the rule above. Both create and validate reach this through
        // buildPaymentAggregate, which is the point: the upper bound used to be enforced only
        // by apportion, and validate deliberately does not call apportion — so /payments/validate
        // answered 200 for an overpayment that /payments then rejected. For a payment gateway
        // that ordering is the whole problem: validate runs before the redirect and create runs
        // after the money has moved.
        if (!properties.overpaymentAllowed() && Amounts.gt(paid, bill.totalAmount)) {
            errMap.put(ErrorCodes.OVERPAYMENT_NOT_ALLOWED,
                    "Amount paid exceeds the amount due for bill: " + billId);
            return false;
        }
        if (bs.allowedPaymentModes != null && !bs.allowedPaymentModes.isEmpty()
                && !bs.allowedPaymentModes.contains(mode)) {
            errMap.put(ErrorCodes.INVALID_PAYMENTDETAIL,
                    "Payment mode %s not allowed for bill: %s".formatted(mode, billId));
            return false;
        }
        if (!Amounts.isIntegral(paid)) {
            errMap.put(ErrorCodes.INVALID_PAYMENTDETAIL,
                    "Amount paid cannot be fractional for bill: " + billId);
            return false;
        }
        if (Amounts.isZero(paid) && bill.totalAmount.signum() > 0) {
            errMap.put(ErrorCodes.INVALID_PAYMENTDETAIL,
                    "Zero payment not allowed for bill with positive amount: " + billId);
            return false;
        }
        return true;
    }

    // ── bill fetch + duplicate-payment guard ──────────────────────────────────

    List<UUID> collectBillIds(List<PaymentRequests.DetailCreate> details) {
        Set<UUID> seen = new LinkedHashSet<>();
        for (PaymentRequests.DetailCreate detail : details) {
            if (!seen.add(detail.billId())) {
                // Q3 fix: was a Go 500
                throw new CustomException(ErrorCodes.DUPLICATE_BILL_ID,
                        "Duplicate bill ID in payment details", detail.billId() + " repeated",
                        null, HttpStatus.BAD_REQUEST);
            }
        }
        return List.copyOf(seen);
    }

    private Map<UUID, Bill> fetchBills(String tenantId, List<UUID> billIds, boolean forUpdate) {
        BillTree tree = forUpdate ? billRepo.getByIdsForUpdate(tenantId, billIds)
                : billRepo.getByIds(tenantId, billIds);
        if (tree.bills().isEmpty()) {
            throw new CustomException(ErrorCodes.INVALID_BILL_ID, "One or more bill IDs not found",
                    null, null, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Map<UUID, List<BillDetailRow>> detailsByBill = new LinkedHashMap<>();
        for (BillDetailRow detail : tree.details()) {
            detailsByBill.computeIfAbsent(detail.billId, k -> new ArrayList<>()).add(detail);
        }
        Map<UUID, Bill> billMap = new LinkedHashMap<>();
        for (BillRow row : tree.bills()) {
            Bill bill = row.toModel(detailsByBill.getOrDefault(row.id, List.of()), tree.accountsByDetail());
            if (bill.status != BillStatus.ACTIVE) {
                throw new CustomException(ErrorCodes.BILL_NOT_ACTIVE, "Bill is not active",
                        "Bill %s is not in ACTIVE status".formatted(bill.id), null,
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (bill.billDetails.isEmpty()) {
                throw new IllegalStateException("Bill ID %s does not contain bill details".formatted(bill.id));
            }
            billMap.put(row.id, bill);
        }
        return billMap;
    }

    private void validateNoDuplicatePayment(String tenantId, List<UUID> billIds) {
        for (PaymentRow payment : repo.getByBillIds(tenantId, billIds)) {
            if (OPEN_INSTRUMENT_STATUSES.contains(payment.instrumentStatus)) {
                throw new CustomException(ErrorCodes.BILL_ALREADY_PAID,
                        "Bill has already been paid or is in pending state", null, null,
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    // ── derived statuses ──────────────────────────────────────────────────────

    static PaymentStatus determinePaymentStatus(PaymentMode mode) {
        return mode.isOnlineFamily() ? PaymentStatus.DEPOSITED : PaymentStatus.NEW;
    }

    static InstrumentStatus determineInstrumentStatus(PaymentMode mode) {
        return mode.isOnlineFamily() ? InstrumentStatus.REMITTED : InstrumentStatus.APPROVED;
    }

    static BillStatus determineBillStatus(BigDecimal total, BigDecimal collected) {
        return Amounts.eq(collected, total) ? BillStatus.PAID : BillStatus.PARTIALLY_PAID;
    }

    static DemandStatus determineDemandStatus(BigDecimal total, BigDecimal collected) {
        return Amounts.eq(collected, total) ? DemandStatus.PAID : DemandStatus.PARTIALLY_PAID;
    }

    // ── apportion reconciliation (Go applyApportionResults) ───────────────────

    static void applyApportionResults(Payment payment, List<Bill> apportioned) {
        Map<UUID, Bill> billById = new HashMap<>();
        for (Bill bill : apportioned) {
            billById.put(bill.id, bill);
        }
        for (Payment.PaymentDetail detail : payment.paymentDetails) {
            Bill apportionedBill = billById.get(detail.billId);
            if (apportionedBill == null) {
                throw new CustomException(ErrorCodes.APPORTION_MISSING_BILL,
                        "bill %s not returned by apportion service".formatted(detail.billId),
                        null, null, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            detail.bill.totalCollectedAmount = apportionedBill.totalCollectedAmount;

            Map<UUID, Bill.BillDetail> detailById = new HashMap<>();
            for (Bill.BillDetail apportionedDetail : apportionedBill.billDetails) {
                detailById.put(apportionedDetail.id, apportionedDetail);
            }
            for (Bill.BillDetail existingDetail : detail.bill.billDetails) {
                Bill.BillDetail apportionedDetail = detailById.get(existingDetail.id);
                if (apportionedDetail == null) {
                    throw new CustomException(ErrorCodes.APPORTION_MISSING_DETAIL,
                            "bill_detail %s not returned".formatted(existingDetail.id),
                            null, null, HttpStatus.INTERNAL_SERVER_ERROR);
                }
                existingDetail.amountPaid = apportionedDetail.amountPaid;

                Map<UUID, Bill.BillAccountDetail> accountById = new HashMap<>();
                for (Bill.BillAccountDetail account : apportionedDetail.billAccountDetails) {
                    accountById.put(account.id, account);
                }
                for (Bill.BillAccountDetail existingAccount : existingDetail.billAccountDetails) {
                    Bill.BillAccountDetail apportionedAccount = accountById.get(existingAccount.id);
                    if (apportionedAccount == null) {
                        throw new CustomException(ErrorCodes.APPORTION_MISSING_ACCOUNT_DETAIL,
                                "%s not returned".formatted(existingAccount.id),
                                null, null, HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    existingAccount.adjustedAmount = apportionedAccount.adjustedAmount;
                }
            }
        }
    }

    // ── persistence application ───────────────────────────────────────────────

    private void applyBillUpdates(Payment payment, String tenantId, String userId, long now) {
        for (Payment.PaymentDetail paymentDetail : payment.paymentDetails) {
            Bill bill = paymentDetail.bill;
            bill.status = determineBillStatus(bill.totalAmount, bill.totalCollectedAmount);

            BillRow billRow = toBillRow(bill, tenantId, userId, now);
            List<BillDetailRow> detailRows = new ArrayList<>();
            List<BillAccountDetailRow> accountRows = new ArrayList<>();
            for (Bill.BillDetail detail : bill.billDetails) {
                BillDetailRow detailRow = new BillDetailRow();
                detailRow.id = detail.id;
                detailRow.tenantId = tenantId;
                detailRow.billId = bill.id;
                detailRow.demandId = detail.demandId;
                detailRow.amount = detail.amount;
                detailRow.amountPaid = detail.amountPaid;
                detailRow.periodFrom = detail.periodFrom;
                detailRow.periodTo = detail.periodTo;
                detailRow.metadata = detail.metadata;
                detailRow.createdBy = billRow.createdBy;
                detailRow.createdTime = billRow.createdTime;
                detailRow.modifiedBy = userId;
                detailRow.modifiedTime = now;
                detailRows.add(detailRow);
                for (Bill.BillAccountDetail account : detail.billAccountDetails) {
                    BillAccountDetailRow accountRow = new BillAccountDetailRow();
                    accountRow.id = account.id;
                    accountRow.tenantId = tenantId;
                    accountRow.billDetailId = detail.id;
                    accountRow.lineItemId = account.lineItemId;
                    accountRow.taxHeadCode = account.taxHeadCode;
                    accountRow.orderNumber = account.order;
                    accountRow.amount = account.amount;
                    accountRow.adjustedAmount = account.adjustedAmount;
                    accountRow.metadata = account.metadata;
                    accountRow.createdBy = billRow.createdBy;
                    accountRow.createdTime = billRow.createdTime;
                    accountRow.modifiedBy = userId;
                    accountRow.modifiedTime = now;
                    accountRows.add(accountRow);
                }
            }

            billRepo.updateFullBillAggregate(billRow, detailRows, accountRows);
            // Go payments path audits the POST-state
            billRepo.insertAudit(billRow, detailRows, accountRows);
        }
    }

    private void applyDemandUpdates(Payment payment, String tenantId, String userId, long now) {
        Map<UUID, BigDecimal> demandDelta = new LinkedHashMap<>();
        Map<UUID, BigDecimal> lineItemDelta = new LinkedHashMap<>();
        for (Payment.PaymentDetail paymentDetail : payment.paymentDetails) {
            for (Bill.BillDetail detail : paymentDetail.bill.billDetails) {
                if (detail.demandId != null) {
                    demandDelta.merge(detail.demandId, detail.amountPaid, BigDecimal::add);
                }
                for (Bill.BillAccountDetail account : detail.billAccountDetails) {
                    lineItemDelta.merge(account.lineItemId, account.adjustedAmount, BigDecimal::add);
                }
            }
        }
        if (demandDelta.isEmpty()) {
            return;
        }

        List<UUID> demandIds = List.copyOf(demandDelta.keySet());
        List<DemandRow> demands = demandRepo.getByIdsForUpdate(tenantId, demandIds);
        if (demands.isEmpty()) {
            throw new IllegalStateException("demand not found");
        }
        List<LineItemRow> items = new ArrayList<>();
        demandRepo.getLineItemsByDemandIds(demandIds).values().forEach(items::addAll);

        // pre-state audits BEFORE mutation (Go captures snapshots first)
        demandRepo.insertAuditBulk(demands, items);

        for (DemandRow demand : demands) {
            BigDecimal delta = demandDelta.getOrDefault(demand.id, BigDecimal.ZERO);
            demand.totalCollectedAmount = demand.totalCollectedAmount.add(delta);
            if (Amounts.gt(demand.totalCollectedAmount, demand.totalAmount)) {
                throw new CustomException(ErrorCodes.OVER_COLLECTION_DETECTED,
                        "Demand over-collection detected", null, null, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            demand.status = determineDemandStatus(demand.totalAmount, demand.totalCollectedAmount);
            demand.isDemandPaid = Amounts.eq(demand.totalCollectedAmount, demand.totalAmount);
            demand.version++;
            demand.modifiedBy = userId;
            demand.modifiedTime = now;
        }
        for (LineItemRow item : items) {
            BigDecimal delta = lineItemDelta.getOrDefault(item.id, BigDecimal.ZERO);
            item.collectedAmount = item.collectedAmount.add(delta);
            if (Amounts.absGreaterThan(item.collectedAmount, item.amount)) {
                throw new CustomException(ErrorCodes.OVER_COLLECTION_LINEITEM,
                        "Line item over-collection detected", null, null, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            item.modifiedBy = userId;
            item.modifiedTime = now;
        }

        demandRepo.updateBatch(demands, items);
    }

    // ── row mapping ───────────────────────────────────────────────────────────

    private static PaymentRow toPaymentRow(Payment payment, String tenantId) {
        PaymentRow row = new PaymentRow();
        row.id = payment.id;
        row.tenantId = tenantId;
        row.totalAmountDue = payment.totalAmountDue;
        row.totalAmountPaid = payment.totalAmountPaid;
        row.transactionNumber = payment.transactionNumber == null ? "" : payment.transactionNumber;
        row.transactionDate = payment.transactionDate == null ? 0 : payment.transactionDate;
        row.paymentMode = payment.paymentMode;
        row.paymentStatus = payment.paymentStatus;
        row.instrumentNumber = payment.instrumentNumber;
        row.instrumentDate = payment.instrumentDate;
        row.instrumentStatus = payment.instrumentStatus;
        row.ifscCode = payment.ifscCode;
        row.paidBy = payment.paidBy;
        row.payerId = payment.payerId;
        row.payerName = payment.payerName;
        row.payerAddress = payment.payerAddress;
        row.payerMobileNumber = payment.payerMobileNumber;
        row.payerEmail = payment.payerEmail;
        row.fileStoreId = payment.fileStoreId;
        row.metadata = payment.metadata;
        row.createdBy = payment.auditDetail.createdBy();
        row.createdTime = payment.auditDetail.createdTime();
        row.modifiedBy = payment.auditDetail.modifiedBy();
        row.modifiedTime = payment.auditDetail.modifiedTime();
        return row;
    }

    private static List<PaymentDetailRow> toDetailRows(Payment payment, String tenantId) {
        List<PaymentDetailRow> rows = new ArrayList<>(payment.paymentDetails.size());
        for (Payment.PaymentDetail detail : payment.paymentDetails) {
            PaymentDetailRow row = new PaymentDetailRow();
            row.id = UUID.randomUUID();
            // review finding #3: reflect the persisted ids in the response (Go emitted
            // zero-UUIDs here — the row ids are strictly more useful; noted in VERIFICATION §6)
            detail.id = row.id;
            detail.paymentId = payment.id;
            row.paymentId = payment.id;
            row.tenantId = tenantId;
            row.billId = detail.billId;
            row.businessServiceCode = detail.businessServiceCode;
            row.totalAmountDue = detail.totalAmountDue;
            row.totalAmountPaid = detail.totalAmountPaid;
            row.receiptNumber = detail.receiptNumber;
            row.receiptDate = detail.receiptDate;
            row.receiptType = detail.receiptType;
            row.manualReceiptNumber = detail.manualReceiptNumber;
            row.manualReceiptDate = detail.manualReceiptDate;
            row.metadata = detail.metadata;
            row.createdBy = payment.auditDetail.createdBy();
            row.createdTime = payment.auditDetail.createdTime();
            row.modifiedBy = payment.auditDetail.modifiedBy();
            row.modifiedTime = payment.auditDetail.modifiedTime();
            rows.add(row);
        }
        return rows;
    }

    private static BillRow toBillRow(Bill bill, String tenantId, String userId, long now) {
        BillRow row = new BillRow();
        row.id = bill.id;
        row.tenantId = tenantId;
        row.businessServiceCode = bill.businessServiceCode;
        row.consumerCode = bill.consumerCode;
        row.payerId = bill.payerId;
        row.payerName = bill.payerName;
        row.payerAddress = bill.payerAddress;
        row.payerMobileNumber = bill.payerMobileNumber;
        row.payerEmail = bill.payerEmail;
        row.billNumber = bill.billNumber;
        row.billIssueAt = bill.billIssueAt;
        row.billExpiryAt = bill.billExpiryAt;
        row.status = bill.status;
        row.totalAmount = bill.totalAmount;
        row.totalCollectedAmount = bill.totalCollectedAmount;
        row.metadata = bill.metadata;
        row.createdBy = bill.auditDetail != null && !bill.auditDetail.createdBy().isEmpty()
                ? bill.auditDetail.createdBy() : userId;
        row.createdTime = bill.auditDetail != null && bill.auditDetail.createdTime() != 0
                ? bill.auditDetail.createdTime() : now;
        row.modifiedBy = userId;
        row.modifiedTime = now;
        return row;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
