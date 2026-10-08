package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class TaxHeadRequests {

    public static final String CODE_PATTERN = "^[A-Z][A-Z0-9_]{1,63}$";

    private TaxHeadRequests() {
    }

    public record Create(
            @NotBlank @Size(min = 2, max = 64) @Pattern(regexp = CODE_PATTERN) String code,
            @NotBlank @Size(min = 2, max = 128) String name,
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            TaxHeadCategory category,
            @JsonProperty("order") @NotNull @Min(1) Integer orderNumber,
            @NotNull @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveFrom,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveTo,
            @NotNull Boolean isActive) {

        public Create {
            category = category == null ? TaxHeadCategory.OTHER : category;
        }

        @AssertTrue(message = "effectiveTo must be strictly greater than effectiveFrom")
        public boolean isEffectiveWindowValid() {
            return effectiveTo == null || effectiveFrom == null || effectiveTo > effectiveFrom;
        }
    }

    public record Update(
            @NotBlank @Size(min = 2, max = 128) String name,
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            TaxHeadCategory category,
            @JsonProperty("order") @NotNull @Min(1) Integer orderNumber,
            @NotNull @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveFrom,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveTo,
            @NotNull Boolean isActive) {

        public Update {
            category = category == null ? TaxHeadCategory.OTHER : category;
        }

        @AssertTrue(message = "effectiveTo must be strictly greater than effectiveFrom")
        public boolean isEffectiveWindowValid() {
            return effectiveTo == null || effectiveFrom == null || effectiveTo > effectiveFrom;
        }
    }

    public record Patch(
            @Size(min = 2, max = 128) String name,
            @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveFrom,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveTo,
            Boolean isActive) {
    }

    public record Filters(String code, TaxHeadCategory category, String businessServiceCode,
                          Boolean isActive, int limit, int offset) {
    }
}
