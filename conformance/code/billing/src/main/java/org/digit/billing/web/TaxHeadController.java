package org.digit.billing.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.TaxHead;
import org.digit.billing.model.TaxHeadCategory;
import org.digit.billing.model.TaxHeadRequests;
import org.digit.billing.service.BillingMetrics;
import org.digit.billing.service.TaxHeadService;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/v3/tax-heads")
public class TaxHeadController {

    private final TaxHeadService service;
    private final BillingMetrics metrics;

    public TaxHeadController(TaxHeadService service, BillingMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<List<TaxHead>> create(
            @RequestHeader(BusinessServiceController.TENANT) String tenantId,
            @RequestHeader(BusinessServiceController.USER) String userId,
            @RequestBody List<TaxHeadRequests.@Valid Create> body) {
        BusinessServiceController.requireNonEmpty(body, "At least one tax head must be provided");
        List<TaxHead> created = service.create(body, tenantId, userId);
        metrics.taxHeadsCreated(tenantId, created.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<TaxHead> search(
            @RequestHeader(BusinessServiceController.TENANT) String tenantId,
            @RequestParam(required = false) @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
            @RequestParam(required = false) TaxHeadCategory category,
            @RequestParam(required = false)
            @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.search(new TaxHeadRequests.Filters(code, category, businessServiceCode,
                isActive, limit, offset), tenantId);
    }

    @GetMapping("/{code}")
    public TaxHead get(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                       @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code) {
        return service.getByCode(code, tenantId)
                .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND, "Tax head not found",
                        null, null, HttpStatus.NOT_FOUND));
    }

    @PutMapping("/{code}")
    public TaxHead update(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                          @RequestHeader(BusinessServiceController.USER) String userId,
                          @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
                          @RequestBody @Valid TaxHeadRequests.Update body) {
        return service.update(code, tenantId, userId, body);
    }

    @PatchMapping("/{code}")
    public TaxHead patch(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                         @RequestHeader(BusinessServiceController.USER) String userId,
                         @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
                         @RequestBody @Valid TaxHeadRequests.Patch body) {
        return service.patch(code, tenantId, userId, body);
    }

    @DeleteMapping("/{code}")
    public Map<String, Boolean> delete(@RequestHeader(BusinessServiceController.TENANT) String tenantId,
                                       @RequestHeader(BusinessServiceController.USER) String userId,
                                       @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code) {
        service.delete(code, tenantId, userId);
        return Map.of("deleted", true);
    }
}
