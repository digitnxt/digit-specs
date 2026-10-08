package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.digit.tracer.model.Error;

public final class DemandRequests {

    private DemandRequests() {
    }

    public record Create(
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @NotNull @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long periodFrom,
            @NotNull @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long periodTo,
            @NotBlank @Size(min = 2, max = 64) String consumerCode,
            @Min(0) @Max(1095) Integer billExpiryDays,
            @Size(max = 10) List<@NotNull @Size(min = 2, max = 64) String> payer,
            @NotEmpty @Size(max = 200) List<@NotNull @Valid LineItemCreate> lineItems,
            DemandStatus status,
            Map<String, Object> metadata,
            List<String> arrearDemandIds) {

        public Create {
            status = status == null ? DemandStatus.ACTIVE : status;
        }

        @AssertTrue(message = "status must be one of DRAFT ACTIVE")
        public boolean isStatusCreatable() {
            return status == DemandStatus.DRAFT || status == DemandStatus.ACTIVE;
        }

        /** Arrear enrichment (Go mutates the item in place) — rebuild with new items/chain. */
        public Create withArrears(List<LineItemCreate> newLineItems, List<String> newArrearDemandIds) {
            return new Create(businessServiceCode, periodFrom, periodTo, consumerCode, billExpiryDays,
                    payer, newLineItems, status, metadata, newArrearDemandIds);
        }
    }

    public record LineItemCreate(
            @NotBlank @Size(min = 2, max = 64) @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String taxHeadCode,
            @NotNull BigDecimal amount,
            BigDecimal collectedAmount,
            Map<String, Object> metadata) {

        public LineItemCreate {
            // Go: non-pointer decimal, absent → zero value
            collectedAmount = collectedAmount == null ? BigDecimal.ZERO : collectedAmount;
        }
    }

    /** Go embeds DemandCreate in DemandUpdate — the JSON is flat, so the record is too. */
    public record Update(
            @NotNull UUID id,
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @NotNull @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long periodFrom,
            @NotNull @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long periodTo,
            @NotBlank @Size(min = 2, max = 64) String consumerCode,
            @Min(0) @Max(1095) Integer billExpiryDays,
            @Size(max = 10) List<@NotNull @Size(min = 2, max = 64) String> payer,
            @NotEmpty @Size(max = 200) List<@NotNull @Valid LineItemCreate> lineItems,
            DemandStatus status,
            Map<String, Object> metadata,
            List<String> arrearDemandIds) {

        public Update {
            status = status == null ? DemandStatus.ACTIVE : status;
        }

        @AssertTrue(message = "status must be one of DRAFT ACTIVE")
        public boolean isStatusCreatable() {
            return status == DemandStatus.DRAFT || status == DemandStatus.ACTIVE;
        }

        public Create asCreate() {
            return new Create(businessServiceCode, periodFrom, periodTo, consumerCode, billExpiryDays,
                    payer, lineItems, status, metadata, arrearDemandIds);
        }
    }

    public record Patch(
            @Size(min = 2, max = 64) String consumerCode,
            @Size(max = 10) List<@NotNull @Size(min = 2, max = 64) String> payer,
            @Size(min = 1, max = 200) List<@NotNull @Valid LineItemCreate> lineItems,
            DemandStatus status) {

        @AssertTrue(message = "status must be one of DRAFT ACTIVE")
        public boolean isStatusPatchable() {
            return status == null || status == DemandStatus.DRAFT || status == DemandStatus.ACTIVE;
        }
    }

    public record CancelRequest(String reasonCode, String note) {
    }

    public record Filters(String businessServiceCode, String consumerCode, DemandStatus status,
                          Long createdFrom, Long createdTo, int limit, int offset) {
    }

    // ── Bulk response shapes (Go writeBulkResponse) ──────────────────────────

    public record BulkFailure(int index, List<Error> errors) {
    }

    public record BulkResponse(
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Demand> success,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<BulkFailure> failures) {
    }
}
