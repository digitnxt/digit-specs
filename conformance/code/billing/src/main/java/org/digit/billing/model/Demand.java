package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Go omitempty on payer/arrearDemandIds/metadata → NON_EMPTY; lineItems/totals always present. */
public record Demand(
        UUID id,
        String businessServiceCode,
        long periodFrom,
        long periodTo,
        String consumerCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer billExpiryDays,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> payer,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> arrearDemandIds,
        List<LineItem> lineItems,
        DemandStatus status,
        BigDecimal totalAmount,
        BigDecimal totalCollectedAmount,
        boolean isDemandPaid,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata,
        int version,
        AuditDetail auditDetail) {

    public record LineItem(
            UUID id,
            UUID demandId,
            String taxHeadCode,
            BigDecimal amount,
            BigDecimal collectedAmount,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata) {
    }
}
