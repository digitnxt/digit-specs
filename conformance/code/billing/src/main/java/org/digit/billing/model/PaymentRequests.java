package org.digit.billing.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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

public final class PaymentRequests {

    private PaymentRequests() {
    }

    public record Create(
            @NotNull BigDecimal totalAmountPaid,
            @Size(max = 128) String transactionNumber,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long transactionDate,
            @NotNull PaymentMode paymentMode,
            @Size(max = 128) String instrumentNumber,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long instrumentDate,
            @Size(max = 64) String ifscCode,
            @NotBlank @Size(max = 128) String paidBy,
            @Size(min = 2, max = 64) String payerId,
            @Size(max = 256) String payerName,
            @Size(max = 1024) String payerAddress,
            @Pattern(regexp = BillRequests.E164_PATTERN) String payerMobileNumber,
            @Email @Size(max = 254) String payerEmail,
            String fileStoreId,
            @NotEmpty List<@NotNull @Valid DetailCreate> paymentDetails,
            Map<String, Object> metadata) {
    }

    public record DetailCreate(
            @NotNull BigDecimal totalAmountPaid,
            @Size(max = 64) String manualReceiptNumber,
            @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long manualReceiptDate,
            @NotNull UUID billId,
            Map<String, Object> metadata) {
    }

    public record Filters(List<UUID> paymentIds, List<UUID> billIds, List<String> receiptNumbers,
                          List<String> consumerCodes, List<PaymentStatus> paymentStatuses,
                          List<InstrumentStatus> instrumentStatuses, List<PaymentMode> paymentModes,
                          List<String> payerIds, String businessServiceCode, String transactionNumber,
                          String payerMobileNumber, Long fromDate, Long toDate, int limit, int offset) {
    }
}
