package org.digit.billing.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.Payment;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentRequests;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.service.BillingMetrics;
import org.digit.billing.service.PaymentService;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/v3/payments")
public class PaymentController {

    private final PaymentService service;
    private final BillingMetrics metrics;

    public PaymentController(PaymentService service, BillingMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<Payment> create(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                                          @RequestHeader(BusinessServiceController.USER) String userId,
                                          @RequestBody @Valid PaymentRequests.Create body) {
        Payment payment = service.create(body, tenantId, userId);
        metrics.paymentCreated(tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(payment);
    }

    @PostMapping("/validate")
    public Payment validate(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                            @RequestHeader(BusinessServiceController.USER) String userId,
                            @RequestBody @Valid PaymentRequests.Create body) {
        return service.validate(body, tenantId, userId);
    }

    @GetMapping
    public List<Payment> search(
            @RequestHeader(BusinessServiceController.TENANT) String tenantId,
            @RequestParam(required = false) String paymentIds,
            @RequestParam(required = false) String billIds,
            @RequestParam(required = false) String receiptNumbers,
            @RequestParam(required = false) String consumerCodes,
            @RequestParam(required = false) String paymentStatuses,
            @RequestParam(required = false) String instrumentStatuses,
            @RequestParam(required = false) String paymentModes,
            @RequestParam(required = false) String payerIds,
            @RequestParam(required = false) String businessServiceCode,
            @RequestParam(required = false) String transactionNumber,
            @RequestParam(required = false) String payerMobileNumber,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long fromDate,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long toDate,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        PaymentRequests.Filters filters = new PaymentRequests.Filters(
                BillController.parseUuidCsv(paymentIds),
                BillController.parseUuidCsv(billIds),
                BillController.parseCsv(receiptNumbers),
                BillController.parseCsv(consumerCodes),
                parseEnumCsv(paymentStatuses, PaymentStatus.class),
                parseEnumCsv(instrumentStatuses, InstrumentStatus.class),
                parseEnumCsv(paymentModes, PaymentMode.class),
                BillController.parseCsv(payerIds),
                businessServiceCode, transactionNumber, payerMobileNumber,
                fromDate, toDate, limit, offset);
        return service.search(filters, tenantId);
    }

    @GetMapping("/{id}")
    public Payment get(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                       @PathVariable String id) {
        return service.getById(parseId(id), tenantId);
    }

    /** Shared with the canonical route so both reject a malformed id identically. */
    static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCodes.INVALID_PATH_PARAM, "Invalid payment ID",
                    e.getMessage(), null, HttpStatus.BAD_REQUEST);
        }
    }

    static <E extends Enum<E>> List<E> parseEnumCsv(String raw, Class<E> type) {
        return BillController.parseCsv(raw).stream()
                .map(value -> {
                    try {
                        return Enum.valueOf(type, value);
                    } catch (IllegalArgumentException e) {
                        throw new CustomException(ErrorCodes.INVALID_REQUEST,
                                "Invalid %s value: %s".formatted(type.getSimpleName(), value),
                                null, List.of(value), HttpStatus.BAD_REQUEST);
                    }
                })
                .toList();
    }
}
