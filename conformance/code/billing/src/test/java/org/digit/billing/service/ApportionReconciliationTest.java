package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.digit.billing.model.Bill;
import org.digit.billing.model.Payment;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;

/** Apportion response reconciliation: every bill/detail/account must return (Go parity). */
class ApportionReconciliationTest {

    private static Bill bill(UUID billId, UUID detailId, UUID accountId) {
        Bill bill = new Bill();
        bill.id = billId;
        bill.totalCollectedAmount = BigDecimal.ZERO;
        Bill.BillDetail detail = new Bill.BillDetail();
        detail.id = detailId;
        detail.amountPaid = BigDecimal.ZERO;
        Bill.BillAccountDetail account = new Bill.BillAccountDetail();
        account.id = accountId;
        account.adjustedAmount = BigDecimal.ZERO;
        detail.billAccountDetails.add(account);
        bill.billDetails.add(detail);
        return bill;
    }

    private static Payment paymentFor(Bill bill) {
        Payment payment = new Payment();
        Payment.PaymentDetail detail = new Payment.PaymentDetail();
        detail.billId = bill.id;
        detail.bill = bill;
        payment.paymentDetails.add(detail);
        return payment;
    }

    @Test
    void appliesCollectedPaidAndAdjustedAmounts() {
        UUID billId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Payment payment = paymentFor(bill(billId, detailId, accountId));

        Bill apportioned = bill(billId, detailId, accountId);
        apportioned.totalCollectedAmount = new BigDecimal("100");
        apportioned.billDetails.get(0).amountPaid = new BigDecimal("100");
        apportioned.billDetails.get(0).billAccountDetails.get(0).adjustedAmount = new BigDecimal("100");

        PaymentService.applyApportionResults(payment, List.of(apportioned));

        Bill applied = payment.paymentDetails.get(0).bill;
        assertEquals(new BigDecimal("100"), applied.totalCollectedAmount);
        assertEquals(new BigDecimal("100"), applied.billDetails.get(0).amountPaid);
        assertEquals(new BigDecimal("100"), applied.billDetails.get(0).billAccountDetails.get(0).adjustedAmount);
    }

    @Test
    void missingBillDetailOrAccountFails() {
        UUID billId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        Payment payment = paymentFor(bill(billId, detailId, accountId));
        CustomException e = assertThrows(CustomException.class,
                () -> PaymentService.applyApportionResults(payment,
                        List.of(bill(UUID.randomUUID(), detailId, accountId))));
        assertEquals("APPORTION_MISSING_BILL", e.getCode());

        Payment payment2 = paymentFor(bill(billId, detailId, accountId));
        e = assertThrows(CustomException.class,
                () -> PaymentService.applyApportionResults(payment2,
                        List.of(bill(billId, UUID.randomUUID(), accountId))));
        assertEquals("APPORTION_MISSING_DETAIL", e.getCode());

        Payment payment3 = paymentFor(bill(billId, detailId, accountId));
        e = assertThrows(CustomException.class,
                () -> PaymentService.applyApportionResults(payment3,
                        List.of(bill(billId, detailId, UUID.randomUUID()))));
        assertEquals("APPORTION_MISSING_ACCOUNT_DETAIL", e.getCode());
    }
}
