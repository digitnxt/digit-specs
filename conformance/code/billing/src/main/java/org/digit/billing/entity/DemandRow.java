package org.digit.billing.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.Demand;
import org.digit.billing.model.DemandStatus;

/** DB rows for demands + line_items. */
public class DemandRow {

    public UUID id;
    public String tenantId;
    public String businessServiceCode;
    public long periodFrom;
    public long periodTo;
    public String consumerCode;
    public Integer billExpiryDays;
    public List<String> payer;
    public List<String> arrearDemandIds;
    public DemandStatus status;
    public BigDecimal totalAmount;
    public BigDecimal totalCollectedAmount;
    public boolean isDemandPaid;
    public Map<String, Object> metadata;
    public int version;
    public String createdBy;
    public long createdTime;
    public String modifiedBy;
    public long modifiedTime;

    public Demand toModel(List<LineItemRow> items) {
        List<Demand.LineItem> lineItems = items == null ? List.of()
                : items.stream().map(LineItemRow::toModel).toList();
        return new Demand(id, businessServiceCode, periodFrom, periodTo, consumerCode, billExpiryDays,
                payer, arrearDemandIds, lineItems, status, totalAmount, totalCollectedAmount,
                isDemandPaid, metadata == null ? Map.of() : metadata, version,
                new AuditDetail(createdBy, createdTime, modifiedBy, modifiedTime));
    }

    public static class LineItemRow {
        public UUID id;
        public String tenantId;
        public UUID demandId;
        public String taxHeadCode;
        public BigDecimal amount;
        public BigDecimal collectedAmount;
        public Map<String, Object> metadata;
        public String createdBy;
        public long createdTime;
        public String modifiedBy;
        public long modifiedTime;

        public Demand.LineItem toModel() {
            return new Demand.LineItem(id, demandId, taxHeadCode, amount, collectedAmount,
                    metadata == null ? Map.of() : metadata);
        }
    }
}
