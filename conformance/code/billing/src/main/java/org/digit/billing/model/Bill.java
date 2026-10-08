package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bill aggregate — mutable class (not a record) because the payment flow
 * mutates it in place exactly as Go does: collected totals before apportion,
 * apportioned amounts after, status on persist. Also the apportion client's
 * request/response payload. Jackson maps the public fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Bill {

    public UUID id;
    public String businessServiceCode;
    public String consumerCode;
    public String payerId;
    public String payerName;
    public String payerAddress;
    public String payerMobileNumber;
    public String payerEmail;
    public String billNumber;
    public long billIssueAt;
    public Long billExpiryAt;
    public BillStatus status;
    public BigDecimal totalAmount;
    public BigDecimal totalCollectedAmount;
    public List<BillDetail> billDetails = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, Object> metadata;
    public AuditDetail auditDetail;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BillDetail {
        public UUID id;
        public UUID billId;
        public UUID demandId;
        public BigDecimal amount;
        public BigDecimal amountPaid;
        public long periodFrom;
        public long periodTo;
        public List<BillAccountDetail> billAccountDetails = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Object> metadata;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BillAccountDetail {
        public UUID id;
        public UUID billDetailId;
        public UUID lineItemId;
        public String taxHeadCode;
        public int order;
        public BigDecimal amount;
        public BigDecimal adjustedAmount;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Object> metadata;
    }
}
