package org.digit.billing.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.BusinessService;
import org.digit.billing.model.CollectionMode;
import org.digit.billing.model.PaymentMode;

/** DB row for business_services (mutable, mirrors the Go struct). */
public class BusinessServiceRow {

    public UUID id;
    public String tenantId;
    public String code;
    public int version;
    public String name;
    public CollectionMode collectionMode;
    public List<PaymentMode> allowedPaymentModes;
    public Integer billExpiryDays;
    public boolean partialPaymentAllowed;
    public BigDecimal minPayableAmount;
    public String currency;
    public String roundingRuleCode;
    public long effectiveFrom;
    public Long effectiveTo;
    public boolean isActive;
    public String createdBy;
    public long createdTime;
    public String modifiedBy;
    public long modifiedTime;

    public BusinessService toModel() {
        return new BusinessService(id, code, version, name, collectionMode, allowedPaymentModes,
                billExpiryDays, partialPaymentAllowed, minPayableAmount, currency, roundingRuleCode,
                effectiveFrom, effectiveTo, isActive,
                new AuditDetail(createdBy, createdTime, modifiedBy, modifiedTime));
    }
}
