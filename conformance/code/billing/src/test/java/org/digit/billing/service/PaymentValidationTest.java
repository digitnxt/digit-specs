package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentRequests;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Instrument rules per mode + derived statuses + Q10 reconciliation + detail-vs-bill matrix. */
class PaymentValidationTest {

    private static final long DAY = 86_400_000L;

    static BillingProperties props() {
        return props(false);
    }

    static BillingProperties props(boolean overpaymentAllowed) {
        return new BillingProperties(false, 90, 10, overpaymentAllowed,
                new BillingProperties.Idgen("http://i", "/g", "/b", "BillNumber", "ReceiptNumber", "TransactionNumber"),
                new BillingProperties.Apportion("http://a", "/p"),
                new BillingProperties.Topics("t", "t-dlq"));
    }

    private final BusinessServiceRepository bsRepo = mock(BusinessServiceRepository.class);
    private final PaymentService service = new PaymentService(null, null, null, bsRepo, null, null, props(), null);

    private static PaymentRequests.Create request(PaymentMode mode, String txnNumber, Long txnDate,
                                                  String instNumber, Long instDate) {
        return new PaymentRequests.Create(BigDecimal.TEN, txnNumber, txnDate, mode, instNumber, instDate,
                null, "payer", null, null, null, null, null, null,
                List.of(new PaymentRequests.DetailCreate(BigDecimal.TEN, null, null, UUID.randomUUID(), null)),
                null);
    }

    private Map<String, String> instrumentErrors(PaymentRequests.Create request) {
        Map<String, String> errMap = new LinkedHashMap<>();
        service.validateInstrument(request, errMap);
        return errMap;
    }

    @Test
    void chequeRequiresNumberAndDate() {
        Map<String, String> errors = instrumentErrors(request(PaymentMode.CHEQUE, null, null, null, null));
        assertTrue(errors.containsKey("INVALID_INST_NUMBER"));
        assertTrue(errors.containsKey("INVALID_INST_DATE"));
    }

    @Test
    void chequeDateAfterTxnAndAgeWindow() {
        long now = System.currentTimeMillis();
        Map<String, String> errors = instrumentErrors(
                request(PaymentMode.DD, null, now, "CHQ1", now + DAY));
        assertTrue(errors.containsKey("INVALID_CHEQUE_DD_DATE"));

        errors = instrumentErrors(request(PaymentMode.CHEQUE, null, now, "CHQ1", now - 91 * DAY));
        assertTrue(errors.containsKey("CHEQUE_DD_DATE_EXCEEDS_MANUAL_RECEIPT"));

        // no txn date → compared to now; Q5: CHEQUE_DD_DATE_IN_FUTURE (not the Go literal)
        errors = instrumentErrors(request(PaymentMode.CHEQUE, null, null, "CHQ1", now + DAY));
        assertTrue(errors.containsKey("CHEQUE_DD_DATE_IN_FUTURE"));

        errors = instrumentErrors(request(PaymentMode.CHEQUE, null, null, "CHQ1", now - 91 * DAY));
        assertTrue(errors.containsKey("CHEQUE_DD_DATE_EXCEEDS_RECEIPT"));

        errors = instrumentErrors(request(PaymentMode.CHEQUE, null, now, "CHQ1", now - DAY));
        assertTrue(errors.isEmpty());
    }

    @Test
    void offlineTransferRules() {
        Map<String, String> errors = instrumentErrors(request(PaymentMode.OFFLINE_NEFT, null, null, null, null));
        assertTrue(errors.containsKey("INVALID_INST_NUMBER"));
        assertTrue(errors.containsKey("INVALID_INST_DATE"));

        errors = instrumentErrors(request(PaymentMode.POSTAL_ORDER, null, null, "N1",
                System.currentTimeMillis() + DAY));
        assertTrue(errors.containsKey("INVALID_NEFT_RTGS_DATE"));
    }

    @Test
    void onlineRequiresTxnAndInstrumentNumbers() {
        Map<String, String> errors = instrumentErrors(request(PaymentMode.UPI, null, null, null, null));
        assertTrue(errors.containsKey("INVALID_TXN_NUMBER"));
        assertTrue(errors.containsKey("INVALID_INSTRUMENT_NUMBER"));

        errors = instrumentErrors(request(PaymentMode.CASH, null, null, null, null));
        assertTrue(errors.isEmpty());
    }

    @Test
    void derivedStatusesByMode() {
        assertEquals(PaymentStatus.DEPOSITED, PaymentService.determinePaymentStatus(PaymentMode.ONLINE));
        assertEquals(InstrumentStatus.REMITTED, PaymentService.determineInstrumentStatus(PaymentMode.WALLET));
        assertEquals(PaymentStatus.NEW, PaymentService.determinePaymentStatus(PaymentMode.CHEQUE));
        assertEquals(InstrumentStatus.APPROVED, PaymentService.determineInstrumentStatus(PaymentMode.CASH));
    }

    @Test
    void paidAndPartiallyPaidTransitionsUseCompareTo() {
        assertEquals(BillStatus.PAID,
                PaymentService.determineBillStatus(new BigDecimal("100.00"), new BigDecimal("100")));
        assertEquals(BillStatus.PARTIALLY_PAID,
                PaymentService.determineBillStatus(new BigDecimal("100"), new BigDecimal("60")));
        // over-collection also PARTIALLY_PAID (Go fallback)
        assertEquals(BillStatus.PARTIALLY_PAID,
                PaymentService.determineBillStatus(new BigDecimal("100"), new BigDecimal("120")));
        assertEquals(DemandStatus.PAID,
                PaymentService.determineDemandStatus(new BigDecimal("50.0"), new BigDecimal("50.00")));
    }

    @Test
    void q10RootTotalMustMatchDetailSum() {
        PaymentRequests.Create mismatched = new PaymentRequests.Create(new BigDecimal("11"), null, null,
                PaymentMode.CASH, null, null, null, "payer", null, null, null, null, null, null,
                List.of(new PaymentRequests.DetailCreate(BigDecimal.TEN, null, null, UUID.randomUUID(), null)),
                null);
        CustomException e = assertThrows(CustomException.class, () -> service.validatePaymentCreate(mismatched));
        assertEquals("INVALID_TOTAL_AMOUNT_PAID", e.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());

        // scale-insensitive match passes
        PaymentRequests.Create matched = new PaymentRequests.Create(new BigDecimal("10.00"), null, null,
                PaymentMode.CASH, null, null, null, "payer", null, null, null, null, null, null,
                List.of(new PaymentRequests.DetailCreate(BigDecimal.TEN, null, null, UUID.randomUUID(), null)),
                null);
        service.validatePaymentCreate(matched);
    }

    @Test
    void q3DuplicateBillIdIs400WithCode() {
        UUID billId = UUID.randomUUID();
        CustomException e = assertThrows(CustomException.class, () -> service.collectBillIds(List.of(
                new PaymentRequests.DetailCreate(BigDecimal.TEN, null, null, billId, null),
                new PaymentRequests.DetailCreate(BigDecimal.TEN, null, null, billId, null))));
        assertEquals("DUPLICATE_BILL_ID", e.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());
    }

    @Test
    void detailAgainstBillMatrix() {
        BusinessServiceRow bs = new BusinessServiceRow();
        bs.code = "PT";
        bs.partialPaymentAllowed = false;
        bs.minPayableAmount = new BigDecimal("5");
        bs.allowedPaymentModes = List.of(PaymentMode.CASH, PaymentMode.ONLINE);
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.of(bs));

        Bill bill = new Bill();
        bill.id = UUID.randomUUID();
        bill.businessServiceCode = "PT";
        bill.totalAmount = new BigDecimal("100.00");

        assertFalse(check(bill, "-1", PaymentMode.CASH).isEmpty());          // negative
        assertFalse(check(bill, "4", PaymentMode.CASH).isEmpty());           // below min payable
        assertFalse(check(bill, "60", PaymentMode.CASH).isEmpty());          // partial forbidden
        assertFalse(check(bill, "150", PaymentMode.CASH).isEmpty());         // overpayment forbidden
        assertFalse(check(bill, "100", PaymentMode.CHEQUE).isEmpty());       // mode not allowed
        assertFalse(check(bill, "100.50", PaymentMode.CASH).isEmpty());      // fractional
        assertTrue(check(bill, "100", PaymentMode.CASH).isEmpty());          // happy

        bill.totalAmount = new BigDecimal("50");
        bs.partialPaymentAllowed = true;
        assertFalse(check(bill, "0", PaymentMode.CASH).isEmpty());           // zero on positive bill

        bill.totalAmount = BigDecimal.ZERO;
        bs.minPayableAmount = null;
        assertTrue(check(bill, "0", PaymentMode.CASH).isEmpty());            // zero on zero bill OK
    }

    private Map<String, String> check(Bill bill, String paid, PaymentMode mode) {
        return check(service, bill, paid, mode);
    }

    private static Map<String, String> check(PaymentService svc, Bill bill, String paid, PaymentMode mode) {
        Map<String, String> errMap = new LinkedHashMap<>();
        svc.validateDetailAgainstBill(mode,
                new PaymentRequests.DetailCreate(new BigDecimal(paid), null, null, bill.id, null),
                bill, "t1", errMap);
        return errMap;
    }

    /**
     * The upper bound used to live only in apportion, which validate deliberately does not
     * call — so /payments/validate answered 200 for an overpayment that /payments rejected.
     * Both routes reach this check through buildPaymentAggregate, so one rule now covers both.
     * The code matches apportion's for the same condition.
     */
    @Test
    void overpaymentIsRejectedWithApportionsCode() {
        BusinessServiceRow bs = new BusinessServiceRow();
        bs.code = "PT";
        bs.partialPaymentAllowed = false;
        bs.allowedPaymentModes = List.of(PaymentMode.CASH);
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.of(bs));

        Bill bill = new Bill();
        bill.id = UUID.randomUUID();
        bill.businessServiceCode = "PT";
        bill.totalAmount = new BigDecimal("500");

        Map<String, String> errors = check(bill, "600", PaymentMode.CASH);
        assertEquals(Set.of("OVERPAYMENT_NOT_ALLOWED"), errors.keySet());
        assertTrue(errors.get("OVERPAYMENT_NOT_ALLOWED").contains(bill.id.toString()));

        assertTrue(check(bill, "500", PaymentMode.CASH).isEmpty());          // exact still fine
    }

    /**
     * billing.overpayment-allowed exists so this cannot silently disagree with apportion's
     * apportion.enable-advance, which is what makes an excess recordable as advance credit.
     */
    @Test
    void overpaymentIsAcceptedWhenTheFlagIsOn() {
        BusinessServiceRow bs = new BusinessServiceRow();
        bs.code = "PT";
        bs.partialPaymentAllowed = false;
        bs.allowedPaymentModes = List.of(PaymentMode.CASH);
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.of(bs));

        Bill bill = new Bill();
        bill.id = UUID.randomUUID();
        bill.businessServiceCode = "PT";
        bill.totalAmount = new BigDecimal("500");

        PaymentService lenient =
                new PaymentService(null, null, null, bsRepo, null, null, props(true), null);
        assertTrue(check(lenient, bill, "600", PaymentMode.CASH).isEmpty());
    }
}
