package org.digit.billing.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.Payment;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.model.ReceiptType;

/** DB rows for payments + payment_details. */
public class PaymentRow {

    public UUID id;
    public String tenantId;
    public BigDecimal totalAmountDue;
    public BigDecimal totalAmountPaid;
    public String transactionNumber;
    public long transactionDate;
    public PaymentMode paymentMode;
    public PaymentStatus paymentStatus;
    public String instrumentNumber;
    public Long instrumentDate;
    public InstrumentStatus instrumentStatus;
    public String ifscCode;
    public String paidBy;
    public String payerId;
    public String payerName;
    public String payerAddress;
    public String payerMobileNumber;
    public String payerEmail;
    public String fileStoreId;
    public Map<String, Object> metadata;
    public String createdBy;
    public long createdTime;
    public String modifiedBy;
    public long modifiedTime;

    public Payment toModel(List<PaymentDetailRow> details) {
        Payment payment = new Payment();
        payment.id = id;
        payment.totalAmountDue = totalAmountDue;
        payment.totalAmountPaid = totalAmountPaid;
        // Go ToModel points at the non-pointer DB fields — always present, "" / 0 included
        payment.transactionNumber = transactionNumber;
        payment.transactionDate = transactionDate;
        payment.paymentMode = paymentMode;
        payment.paymentStatus = paymentStatus;
        payment.instrumentNumber = instrumentNumber;
        payment.instrumentDate = instrumentDate;
        payment.instrumentStatus = instrumentStatus;
        payment.ifscCode = ifscCode;
        payment.paidBy = paidBy;
        payment.payerId = payerId;
        payment.payerName = payerName;
        payment.payerAddress = payerAddress;
        payment.payerMobileNumber = payerMobileNumber;
        payment.payerEmail = payerEmail;
        payment.fileStoreId = fileStoreId;
        payment.metadata = metadata == null ? Map.of() : metadata;
        payment.auditDetail = new AuditDetail(createdBy, createdTime, modifiedBy, modifiedTime);
        if (details != null) {
            for (PaymentDetailRow detail : details) {
                payment.paymentDetails.add(detail.toModel());
            }
        }
        return payment;
    }

    public static class PaymentDetailRow {
        public UUID id;
        public UUID paymentId;
        public String tenantId;
        public UUID billId;
        public String businessServiceCode;
        public BigDecimal totalAmountDue;
        public BigDecimal totalAmountPaid;
        public String receiptNumber;
        public long receiptDate;
        public ReceiptType receiptType;
        public String manualReceiptNumber;
        public Long manualReceiptDate;
        public Map<String, Object> metadata;
        public String createdBy;
        public long createdTime;
        public String modifiedBy;
        public long modifiedTime;

        public Payment.PaymentDetail toModel() {
            Payment.PaymentDetail detail = new Payment.PaymentDetail();
            detail.id = id;
            detail.paymentId = paymentId;
            detail.totalAmountDue = totalAmountDue;
            detail.totalAmountPaid = totalAmountPaid;
            detail.manualReceiptNumber = manualReceiptNumber;
            detail.manualReceiptDate = manualReceiptDate;
            detail.receiptNumber = receiptNumber;
            detail.receiptDate = receiptDate;
            detail.receiptType = receiptType;
            detail.businessServiceCode = businessServiceCode;
            detail.billId = billId;
            detail.metadata = metadata == null ? Map.of() : metadata;
            return detail;
        }
    }
}
