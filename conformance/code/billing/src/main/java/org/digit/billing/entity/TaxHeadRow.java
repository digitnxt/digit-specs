package org.digit.billing.entity;

import java.util.UUID;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.TaxHead;
import org.digit.billing.model.TaxHeadCategory;

/** DB row for tax_heads. */
public class TaxHeadRow {

    public UUID id;
    public String tenantId;
    public String code;
    public int version;
    public String name;
    public String businessServiceCode;
    public TaxHeadCategory category;
    public int orderNumber;
    public long effectiveFrom;
    public Long effectiveTo;
    public boolean isActive;
    public String createdBy;
    public long createdTime;
    public String modifiedBy;
    public long modifiedTime;

    public TaxHead toModel() {
        return new TaxHead(id, code, version, name, businessServiceCode, category, orderNumber,
                effectiveFrom, effectiveTo, isActive,
                new AuditDetail(createdBy, createdTime, modifiedBy, modifiedTime));
    }
}
