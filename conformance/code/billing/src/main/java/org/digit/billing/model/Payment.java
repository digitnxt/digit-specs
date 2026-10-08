package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Payment aggregate — mutable for the same reason as {@link Bill}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Payment {

    public UUID id;
    public BigDecimal totalAmountDue;
    public BigDecimal totalAmountPaid;
    public String transactionNumber;
    public Long transactionDate;
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
    public List<PaymentDetail> paymentDetails = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, Object> metadata;
    public AuditDetail auditDetail;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PaymentDetail {
        public UUID id;
        public UUID paymentId;
        public BigDecimal totalAmountDue;
        public BigDecimal totalAmountPaid;
        public String manualReceiptNumber;
        public Long manualReceiptDate;
        public String receiptNumber;
        public long receiptDate;
        public ReceiptType receiptType;
        public String businessServiceCode;
        public UUID billId;
        /** Set on create/validate responses (Go parity); absent on search/get. */
        public Bill bill;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Object> metadata;
    }
}
