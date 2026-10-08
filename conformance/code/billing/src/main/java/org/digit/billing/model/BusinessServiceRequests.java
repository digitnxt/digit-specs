package org.digit.billing.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Request DTOs — validation rules 1:1 with the Go binding tags (DISCOVERY §2). */
public final class BusinessServiceRequests {

    public static final String CODE_PATTERN = "^[A-Z][A-Z0-9_]{1,31}$";
    public static final long EPOCH_MAX = 9007199254740991L;

    private BusinessServiceRequests() {
    }

    public record Create(
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = CODE_PATTERN) String code,
            @NotBlank @Size(min = 2, max = 128) String name,
            CollectionMode collectionMode,
            @NotEmpty List<@NotNull PaymentMode> allowedPaymentModes,
            @NotNull @Min(0) @Max(1095) Integer billExpiryDays,
            Boolean partialPaymentAllowed,
            @DecimalMin("0") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal minPayableAmount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            String roundingRuleCode,
            @NotNull @Min(0) @Max(EPOCH_MAX) Long effectiveFrom,
            @Min(0) @Max(EPOCH_MAX) Long effectiveTo,
            @NotNull Boolean isActive) {

        public Create {
            // Go ApplyDefaults: collectionMode "" → BOTH (isActive is binding-required, default never fires)
            collectionMode = collectionMode == null ? CollectionMode.BOTH : collectionMode;
            partialPaymentAllowed = partialPaymentAllowed != null && partialPaymentAllowed;
        }

        @AssertTrue(message = "effectiveTo must be strictly greater than effectiveFrom")
        public boolean isEffectiveWindowValid() {
            return effectiveTo == null || effectiveFrom == null || effectiveTo > effectiveFrom;
        }
    }

    public record Update(
            @NotBlank @Size(min = 2, max = 128) String name,
            CollectionMode collectionMode,
            @NotEmpty List<@NotNull PaymentMode> allowedPaymentModes,
            @NotNull @Min(0) @Max(1095) Integer billExpiryDays,
            Boolean partialPaymentAllowed,
            @DecimalMin("0") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal minPayableAmount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            String roundingRuleCode,
            @NotNull @Min(0) @Max(EPOCH_MAX) Long effectiveFrom,
            @Min(0) @Max(EPOCH_MAX) Long effectiveTo,
            @NotNull Boolean isActive) {

        public Update {
            collectionMode = collectionMode == null ? CollectionMode.BOTH : collectionMode;
            partialPaymentAllowed = partialPaymentAllowed != null && partialPaymentAllowed;
        }

        @AssertTrue(message = "effectiveTo must be strictly greater than effectiveFrom")
        public boolean isEffectiveWindowValid() {
            return effectiveTo == null || effectiveFrom == null || effectiveTo > effectiveFrom;
        }
    }

    public record Patch(
            @Size(min = 2, max = 128) String name,
            CollectionMode collectionMode,
            @Size(min = 1) List<@NotNull PaymentMode> allowedPaymentModes,
            @Min(0) @Max(1095) Integer billExpiryDays,
            @DecimalMin("0") @DecimalMax("9999999.99") @Digits(integer = 7, fraction = 2) BigDecimal minPayableAmount,
            String roundingRuleCode,
            @Min(0) @Max(EPOCH_MAX) Long effectiveFrom,
            @Min(0) @Max(EPOCH_MAX) Long effectiveTo,
            Boolean isActive) {
    }

    /** Search filters; limit default 25 (Go ApplyDefaults), bounds enforced at the controller. */
    public record Filters(String code, Boolean isActive, Long effectiveOn, int limit, int offset) {
    }
}
