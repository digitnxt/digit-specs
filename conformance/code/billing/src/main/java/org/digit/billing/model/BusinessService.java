package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** API response shape — field set and omission rules mirror Go's BusinessService. */
public record BusinessService(
        UUID id,
        String code,
        int version,
        String name,
        CollectionMode collectionMode,
        List<PaymentMode> allowedPaymentModes,
        Integer billExpiryDays,
        boolean partialPaymentAllowed,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal minPayableAmount,
        String currency,
        @JsonInclude(JsonInclude.Include.NON_NULL) String roundingRuleCode,
        long effectiveFrom,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveTo,
        boolean isActive,
        AuditDetail auditDetail) {
}
