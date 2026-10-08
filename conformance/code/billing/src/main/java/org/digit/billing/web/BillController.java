package org.digit.billing.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.service.BillService;
import org.digit.billing.service.BillingMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/v3/bills")
public class BillController {

    private final BillService service;
    private final BillingMetrics metrics;

    public BillController(BillService service, BillingMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping("/generate")
    public ResponseEntity<Bill> generate(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                                         @RequestHeader(BusinessServiceController.USER) String userId,
                                         @RequestBody @Valid BillRequests.GenerateBillCriteria body) {
        Bill bill = service.generate(body, tenantId, userId);
        metrics.billGenerated(tenantId, body.businessServiceCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(bill);
    }

    @GetMapping
    public List<Bill> search(
            @RequestHeader(BusinessServiceController.TENANT) String tenantId,
            @RequestParam(required = false)
            @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @RequestParam(required = false) String consumerCodes,
            @RequestParam(required = false) String billNumbers,
            @RequestParam(required = false) String billIds,
            @RequestParam(required = false) BillStatus status,
            @RequestParam(required = false) @Pattern(regexp = BillRequests.E164_PATTERN) String mobileNumber,
            @RequestParam(required = false) @Email String email,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.search(new BillRequests.Filters(businessServiceCode,
                parseCsv(consumerCodes), parseCsv(billNumbers), parseUuidCsv(billIds),
                status, mobileNumber, email, limit, offset), tenantId);
    }

    @PostMapping("/cancel")
    public Bill cancel(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                       @RequestHeader(BusinessServiceController.USER) String userId,
                       @RequestBody @Valid BillRequests.UpdateBillStatus body) {
        Bill bill = service.cancel(body, tenantId, userId);
        metrics.billCancelled(tenantId, body.businessServiceCode());
        return bill;
    }

    @PostMapping("/bulk-generate")
    public ResponseEntity<BillRequests.BulkBillResponse> bulkGenerate(
            @RequestHeader(BusinessServiceController.TENANT) String tenantId,
            @RequestHeader(BusinessServiceController.USER) String userId,
            @RequestBody @Valid BillRequests.BulkBillGenerator body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.bulkGenerate(body, tenantId, userId));
    }

    /** Go parseQueryList: comma-split, trimmed, empties dropped. */
    static List<String> parseCsv(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Go parseUUIDList: invalid UUIDs silently dropped. */
    static List<UUID> parseUuidCsv(String raw) {
        List<UUID> out = new ArrayList<>();
        for (String part : parseCsv(raw)) {
            try {
                out.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // Go parity: skip
            }
        }
        return out;
    }
}
