package org.digit.billing.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.Demand;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.service.BillingMetrics;
import org.digit.billing.service.DemandService;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/v3/demands")
public class DemandController {

    private final DemandService service;
    private final BillingMetrics metrics;

    public DemandController(DemandService service, BillingMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                                    @RequestHeader(BusinessServiceController.USER) String userId,
                                    @RequestBody List<DemandRequests.@Valid Create> body) {
        BusinessServiceController.requireNonEmpty(body, "At least one demand must be provided");
        DemandRequests.BulkResponse response = service.create(body, tenantId, userId);
        if (response.failures() == null || response.failures().isEmpty()) {
            metrics.demandCreated(tenantId, "");
        }
        return BulkResponses.write(HttpStatus.CREATED, response);
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                                    @RequestHeader(BusinessServiceController.USER) String userId,
                                    @RequestBody List<DemandRequests.@Valid Update> body) {
        BusinessServiceController.requireNonEmpty(body, "At least one demand must be provided");
        DemandRequests.BulkResponse response = service.update(body, tenantId, userId);
        if (response.failures() == null || response.failures().isEmpty()) {
            metrics.demandUpdated(tenantId, "");
        }
        return BulkResponses.write(HttpStatus.OK, response);
    }

    @GetMapping
    public List<Demand> search(
            @RequestHeader(BusinessServiceController.TENANT) String tenantId,
            @RequestParam(required = false)
            @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @RequestParam(required = false) @Size(min = 2, max = 64) String consumerCode,
            @RequestParam(required = false) DemandStatus status,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long createdFrom,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long createdTo,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.search(new DemandRequests.Filters(businessServiceCode, consumerCode, status,
                createdFrom, createdTo, limit, offset), tenantId);
    }

    @GetMapping("/{id}")
    public Demand get(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                      @PathVariable String id) {
        return service.getById(parseId(id), tenantId);
    }

    @PatchMapping("/{id}")
    public Demand patch(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                        @RequestHeader(BusinessServiceController.USER) String userId,
                        @PathVariable String id,
                        @RequestBody @Valid DemandRequests.Patch body) {
        return service.patch(parseId(id), tenantId, userId, body);
    }

    @PostMapping("/{id}/freeze")
    public Demand freeze(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                         @RequestHeader(BusinessServiceController.USER) String userId,
                         @PathVariable String id) {
        Demand demand = service.freeze(parseId(id), tenantId, userId);
        metrics.demandFrozen(tenantId);
        return demand;
    }

    @PostMapping("/{id}/cancel")
    public Demand cancel(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                         @RequestHeader(BusinessServiceController.USER) String userId,
                         @PathVariable String id,
                         @RequestBody(required = false) DemandRequests.CancelRequest body) {
        Demand demand = service.cancel(parseId(id), tenantId, userId, body);
        metrics.demandCancelled(tenantId);
        return demand;
    }

    static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCodes.INVALID_PATH_PARAM, "Demand id must be a valid UUID",
                    e.getMessage(), null, HttpStatus.BAD_REQUEST);
        }
    }
}
