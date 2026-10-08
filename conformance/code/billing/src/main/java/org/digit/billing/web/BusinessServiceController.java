package org.digit.billing.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.digit.billing.model.BusinessService;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.service.BillingMetrics;
import org.digit.billing.service.BusinessServiceService;
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
@RequestMapping("/v3/business-services")
public class BusinessServiceController {

    static final String TENANT = HeaderInterceptor.TENANT_ID;
    static final String USER = HeaderInterceptor.USER_ID;

    private final BusinessServiceService service;
    private final BillingMetrics metrics;

    public BusinessServiceController(BusinessServiceService service, BillingMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<List<BusinessService>> create(
            @RequestHeader(TENANT) String tenantId,
            @RequestHeader(USER) String userId,
            @RequestBody List<BusinessServiceRequests.@Valid Create> body) {
        requireNonEmpty(body, "At least one business service must be provided");
        List<BusinessService> created = service.create(body, tenantId, userId);
        metrics.businessServicesCreated(tenantId, created.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<BusinessService> search(
            @RequestHeader(TENANT) String tenantId,
            @RequestParam(required = false) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveOn,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.search(new BusinessServiceRequests.Filters(code, isActive, effectiveOn, limit, offset),
                tenantId);
    }

    @GetMapping("/{code}")
    public BusinessService get(@RequestHeader(TENANT) String tenantId,
                               @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code) {
        return service.getByCode(code, tenantId)
                .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND, "Business service not found",
                        null, null, HttpStatus.NOT_FOUND));
    }

    @PutMapping("/{code}")
    public BusinessService update(@RequestHeader(TENANT) String tenantId,
                                  @RequestHeader(USER) String userId,
                                  @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
                                  @RequestBody @Valid BusinessServiceRequests.Update body) {
        return service.update(code, tenantId, userId, body);
    }

    @PatchMapping("/{code}")
    public BusinessService patch(@RequestHeader(TENANT) String tenantId,
                                 @RequestHeader(USER) String userId,
                                 @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
                                 @RequestBody @Valid BusinessServiceRequests.Patch body) {
        return service.patch(code, tenantId, userId, body);
    }

    @DeleteMapping("/{code}")
    public Map<String, Boolean> delete(@RequestHeader(TENANT) String tenantId,
                                       @RequestHeader(USER) String userId,
                                       @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code) {
        service.delete(code, tenantId, userId);
        return Map.of("deleted", true);
    }

    static void requireNonEmpty(List<?> body, String message) {
        if (body == null || body.isEmpty()) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST, message, null, null, HttpStatus.BAD_REQUEST);
        }
    }
}
