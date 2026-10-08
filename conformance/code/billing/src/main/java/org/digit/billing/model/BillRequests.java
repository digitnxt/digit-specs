package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BillRequests {

    /** go-playground's e164 regex, verbatim (HR-10). */
    public static final String E164_PATTERN = "^\\+[1-9]?[0-9]{7,14}$";

    private BillRequests() {
    }

    public record GenerateBillCriteria(
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @NotBlank @Size(min = 2, max = 64) String consumerCode,
            @Size(min = 2, max = 64) String payerId,
            @Size(max = 256) String payerName,
            @Size(max = 1024) String payerAddress,
            @Pattern(regexp = E164_PATTERN) String payerMobileNumber,
            @Email @Size(max = 254) String payerEmail) {
    }

    public record UpdateBillStatus(
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @NotBlank @Size(min = 2, max = 64) String consumerCode,
            @NotNull BillStatus statusToBeUpdated,
            @NotNull Map<String, Object> metadata) {
    }

    public record BulkBillGenerator(
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            Map<String, Object> metadata) {
    }

    public record BulkBillResponse(
            String requestId,
            String businessServiceCode,
            BulkBillStatus status,
            int totalJobsCreated,
            int totalConsumersIdentified,
            Map<String, Object> metadata) {
    }

    /** PubSub job payload — field names match the Go envelope's data object exactly. */
    public record BulkBillGenerationJob(
            String id,
            String requestId,
            String tenantId,
            String businessServiceCode,
            List<String> consumerCodes,
            String userId,
            int batchNumber,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata) {
    }

    public record Filters(String businessServiceCode, List<String> consumerCodes, List<String> billNumbers,
                          List<UUID> billIds, BillStatus status, String mobileNumber, String email,
                          int limit, int offset) {
    }
}
