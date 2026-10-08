package org.digit.billing.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillStatus;

/** DB rows for bills + bill_details + bill_account_details. */
public class BillRow {

    public UUID id;
    public String tenantId;
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
    public Map<String, Object> metadata;
    public String createdBy;
    public long createdTime;
    public String modifiedBy;
    public long modifiedTime;

    public Bill toModel(List<BillDetailRow> details, Map<UUID, List<BillAccountDetailRow>> accountsByDetail) {
        Bill bill = new Bill();
        bill.id = id;
        bill.businessServiceCode = businessServiceCode;
        bill.consumerCode = consumerCode;
        bill.payerId = payerId;
        bill.payerName = payerName;
        bill.payerAddress = payerAddress;
        bill.payerMobileNumber = payerMobileNumber;
        bill.payerEmail = payerEmail;
        bill.billNumber = billNumber;
        bill.billIssueAt = billIssueAt;
        bill.billExpiryAt = billExpiryAt;
        bill.status = status;
        bill.totalAmount = totalAmount;
        bill.totalCollectedAmount = totalCollectedAmount;
        bill.metadata = metadata == null ? Map.of() : metadata;
        bill.auditDetail = new AuditDetail(createdBy, createdTime, modifiedBy, modifiedTime);
        if (details != null) {
            for (BillDetailRow detail : details) {
                bill.billDetails.add(detail.toModel(
                        accountsByDetail == null ? List.of() : accountsByDetail.getOrDefault(detail.id, List.of())));
            }
        }
        return bill;
    }

    public static class BillDetailRow {
        public UUID id;
        public String tenantId;
        public UUID billId;
        public UUID demandId;
        public BigDecimal amount;
        public BigDecimal amountPaid;
        public long periodFrom;
        public long periodTo;
        public Map<String, Object> metadata;
        public String createdBy;
        public long createdTime;
        public String modifiedBy;
        public long modifiedTime;

        public Bill.BillDetail toModel(List<BillAccountDetailRow> accounts) {
            Bill.BillDetail detail = new Bill.BillDetail();
            detail.id = id;
            detail.billId = billId;
            detail.demandId = demandId;
            detail.amount = amount;
            detail.amountPaid = amountPaid;
            detail.periodFrom = periodFrom;
            detail.periodTo = periodTo;
            detail.metadata = metadata == null ? Map.of() : metadata;
            for (BillAccountDetailRow account : accounts) {
                detail.billAccountDetails.add(account.toModel());
            }
            return detail;
        }
    }

    public static class BillAccountDetailRow {
        public UUID id;
        public String tenantId;
        public UUID billDetailId;
        public UUID lineItemId;
        public String taxHeadCode;
        public int orderNumber;
        public BigDecimal amount;
        public BigDecimal adjustedAmount;
        public Map<String, Object> metadata;
        public String createdBy;
        public long createdTime;
        public String modifiedBy;
        public long modifiedTime;

        public Bill.BillAccountDetail toModel() {
            Bill.BillAccountDetail account = new Bill.BillAccountDetail();
            account.id = id;
            account.billDetailId = billDetailId;
            account.lineItemId = lineItemId;
            account.taxHeadCode = taxHeadCode;
            account.order = orderNumber;
            account.amount = amount;
            account.adjustedAmount = adjustedAmount;
            account.metadata = metadata == null ? Map.of() : metadata;
            return account;
        }
    }
}
