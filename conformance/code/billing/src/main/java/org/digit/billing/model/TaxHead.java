package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record TaxHead(
        UUID id,
        String code,
        int version,
        String name,
        String businessServiceCode,
        TaxHeadCategory category,
        @JsonProperty("order") int orderNumber,
        long effectiveFrom,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveTo,
        boolean isActive,
        AuditDetail auditDetail) {
}
