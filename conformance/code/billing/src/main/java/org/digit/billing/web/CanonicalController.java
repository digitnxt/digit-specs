package org.digit.billing.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.BusinessService;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.CanonicalDtos;
import org.digit.billing.model.Demand;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.ErrorCodes;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.Payment;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.model.TaxHead;
import org.digit.billing.model.TaxHeadCategory;
import org.digit.billing.model.TaxHeadRequests;
import org.digit.billing.service.BillService;
import org.digit.billing.service.BillingMetrics;
import org.digit.billing.service.BusinessServiceService;
import org.digit.billing.service.DemandService;
import org.digit.billing.service.PaymentService;
import org.digit.billing.service.TaxHeadService;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.model.Error;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical (header-free) routes of billing-canonical.yml — one twin for each of the 27
 * operations the five 3.0 controllers serve. Pure adapter: every request datum those
 * routes take as an X-* header is read out of {@code requestMetadata} instead, then the
 * same service methods run with the same arguments and the same metrics are recorded.
 *
 * <p>Query parameters stay query parameters and resource identifiers stay path
 * parameters — neither is request metadata — so search, get, delete and freeze carry only
 * {@code requestMetadata} in the body, and every criterion keeps the 3.0 route's own
 * validation annotations and parsing helpers rather than a second copy of them.
 *
 * <p>Tenant is promoted back to X-Tenant-ID by {@link CanonicalTenantFilter} before the
 * request reaches here, so tenant-migration's transaction and search_path work unchanged.
 * User id is NOT promoted — it is validated here, on exactly the operations
 * {@link HeaderInterceptor} demands X-User-ID for (every non-GET), so the error names the
 * body field the caller actually got wrong rather than a header they never sent.
 */
@RestController
@Validated
public class CanonicalController {

    /**
     * Configurable path segment, {@code canonical} by default. A compile-time constant so
     * it drives both the mappings below and {@code @Value} in
     * {@link org.digit.billing.config.WebConfig} — the interceptor's exclude patterns are
     * not placeholder-resolved.
     */
    public static final String PATH = "${billing.canonical-path:canonical}";

    private static final String BUSINESS_SERVICES = "/v3/" + PATH + "/business-services";
    private static final String TAX_HEADS = "/v3/" + PATH + "/tax-heads";
    private static final String DEMANDS = "/v3/" + PATH + "/demands";
    private static final String BILLS = "/v3/" + PATH + "/bills";
    private static final String PAYMENTS = "/v3/" + PATH + "/payments";

    /** Key read out of the opaque userInfo map. */
    static final String USER_ID = "userId";

    private final BusinessServiceService businessServices;
    private final TaxHeadService taxHeads;
    private final DemandService demands;
    private final BillService bills;
    private final PaymentService payments;
    private final BillingMetrics metrics;

    public CanonicalController(BusinessServiceService businessServices, TaxHeadService taxHeads,
                               DemandService demands, BillService bills, PaymentService payments,
                               BillingMetrics metrics) {
        this.businessServices = businessServices;
        this.taxHeads = taxHeads;
        this.demands = demands;
        this.bills = bills;
        this.payments = payments;
        this.metrics = metrics;
    }

    // ── Business services ───────────────────────────────────────────────────────

    @PostMapping(BUSINESS_SERVICES)
    @ResponseStatus(HttpStatus.CREATED)
    public CanonicalDtos.BusinessServicesResponse createBusinessServices(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.BusinessServiceCreateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        String userId = userId(in);
        BusinessServiceController.requireNonEmpty(body.data(), "At least one business service must be provided");
        List<BusinessService> created = businessServices.create(body.data(), in.tenantId(), userId);
        metrics.businessServicesCreated(in.tenantId(), created.size());
        return new CanonicalDtos.BusinessServicesResponse(success(http, in), created);
    }

    @GetMapping(BUSINESS_SERVICES)
    public CanonicalDtos.BusinessServicesResponse searchBusinessServices(
            HttpServletRequest http,
            @RequestParam(required = false) @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long effectiveOn,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        List<BusinessService> found = businessServices.search(
                new BusinessServiceRequests.Filters(code, isActive, effectiveOn, limit, offset), in.tenantId());
        return new CanonicalDtos.BusinessServicesResponse(success(http, in), found);
    }

    @GetMapping(BUSINESS_SERVICES + "/{code}")
    public CanonicalDtos.BusinessServiceResponse getBusinessService(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        BusinessService found = businessServices.getByCode(code, in.tenantId())
                .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND, "Business service not found",
                        null, null, HttpStatus.NOT_FOUND));
        return new CanonicalDtos.BusinessServiceResponse(success(http, in), found);
    }

    @PutMapping(BUSINESS_SERVICES + "/{code}")
    public CanonicalDtos.BusinessServiceResponse updateBusinessService(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.BusinessServiceUpdateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.BusinessServiceResponse(success(http, in),
                businessServices.update(code, in.tenantId(), userId(in), body.data()));
    }

    @PatchMapping(BUSINESS_SERVICES + "/{code}")
    public CanonicalDtos.BusinessServiceResponse patchBusinessService(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.BusinessServicePatchRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.BusinessServiceResponse(success(http, in),
                businessServices.patch(code, in.tenantId(), userId(in), body.data()));
    }

    @DeleteMapping(BUSINESS_SERVICES + "/{code}")
    public CanonicalDtos.DeleteResponse deleteBusinessService(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        businessServices.delete(code, in.tenantId(), userId(in));
        return new CanonicalDtos.DeleteResponse(success(http, in), Map.of("deleted", true));
    }

    // ── Tax heads ───────────────────────────────────────────────────────────────

    @PostMapping(TAX_HEADS)
    @ResponseStatus(HttpStatus.CREATED)
    public CanonicalDtos.TaxHeadsResponse createTaxHeads(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.TaxHeadCreateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        String userId = userId(in);
        BusinessServiceController.requireNonEmpty(body.data(), "At least one tax head must be provided");
        List<TaxHead> created = taxHeads.create(body.data(), in.tenantId(), userId);
        metrics.taxHeadsCreated(in.tenantId(), created.size());
        return new CanonicalDtos.TaxHeadsResponse(success(http, in), created);
    }

    @GetMapping(TAX_HEADS)
    public CanonicalDtos.TaxHeadsResponse searchTaxHeads(
            HttpServletRequest http,
            @RequestParam(required = false) @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
            @RequestParam(required = false) TaxHeadCategory category,
            @RequestParam(required = false)
            @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        List<TaxHead> found = taxHeads.search(new TaxHeadRequests.Filters(code, category, businessServiceCode,
                isActive, limit, offset), in.tenantId());
        return new CanonicalDtos.TaxHeadsResponse(success(http, in), found);
    }

    @GetMapping(TAX_HEADS + "/{code}")
    public CanonicalDtos.TaxHeadResponse getTaxHead(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        TaxHead found = taxHeads.getByCode(code, in.tenantId())
                .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND, "Tax head not found",
                        null, null, HttpStatus.NOT_FOUND));
        return new CanonicalDtos.TaxHeadResponse(success(http, in), found);
    }

    @PutMapping(TAX_HEADS + "/{code}")
    public CanonicalDtos.TaxHeadResponse updateTaxHead(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.TaxHeadUpdateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.TaxHeadResponse(success(http, in),
                taxHeads.update(code, in.tenantId(), userId(in), body.data()));
    }

    @PatchMapping(TAX_HEADS + "/{code}")
    public CanonicalDtos.TaxHeadResponse patchTaxHead(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.TaxHeadPatchRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.TaxHeadResponse(success(http, in),
                taxHeads.patch(code, in.tenantId(), userId(in), body.data()));
    }

    @DeleteMapping(TAX_HEADS + "/{code}")
    public CanonicalDtos.DeleteResponse deleteTaxHead(
            HttpServletRequest http,
            @PathVariable @Pattern(regexp = TaxHeadRequests.CODE_PATTERN) String code,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        taxHeads.delete(code, in.tenantId(), userId(in));
        return new CanonicalDtos.DeleteResponse(success(http, in), Map.of("deleted", true));
    }

    // ── Demands ─────────────────────────────────────────────────────────────────

    @PostMapping(DEMANDS)
    public ResponseEntity<Object> createDemands(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.DemandCreateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        String userId = userId(in);
        BusinessServiceController.requireNonEmpty(body.data(), "At least one demand must be provided");
        DemandRequests.BulkResponse response = demands.create(body.data(), in.tenantId(), userId);
        if (response.failures() == null || response.failures().isEmpty()) {
            metrics.demandCreated(in.tenantId(), "");
        }
        return bulk(http, in, BulkResponses.write(HttpStatus.CREATED, response));
    }

    @PutMapping(DEMANDS)
    public ResponseEntity<Object> updateDemands(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.DemandUpdateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        String userId = userId(in);
        BusinessServiceController.requireNonEmpty(body.data(), "At least one demand must be provided");
        DemandRequests.BulkResponse response = demands.update(body.data(), in.tenantId(), userId);
        if (response.failures() == null || response.failures().isEmpty()) {
            metrics.demandUpdated(in.tenantId(), "");
        }
        return bulk(http, in, BulkResponses.write(HttpStatus.OK, response));
    }

    @GetMapping(DEMANDS)
    public CanonicalDtos.DemandsResponse searchDemands(
            HttpServletRequest http,
            @RequestParam(required = false)
            @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @RequestParam(required = false) @Size(min = 2, max = 64) String consumerCode,
            @RequestParam(required = false) DemandStatus status,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long createdFrom,
            @RequestParam(required = false) @Min(0) @Max(BusinessServiceRequests.EPOCH_MAX) Long createdTo,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        List<Demand> found = demands.search(new DemandRequests.Filters(businessServiceCode, consumerCode,
                status, createdFrom, createdTo, limit, offset), in.tenantId());
        return new CanonicalDtos.DemandsResponse(success(http, in), found);
    }

    @GetMapping(DEMANDS + "/{id}")
    public CanonicalDtos.DemandResponse getDemand(
            HttpServletRequest http, @PathVariable String id,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.DemandResponse(success(http, in),
                demands.getById(DemandController.parseId(id), in.tenantId()));
    }

    @PatchMapping(DEMANDS + "/{id}")
    public CanonicalDtos.DemandResponse patchDemand(
            HttpServletRequest http, @PathVariable String id,
            @Valid @RequestBody CanonicalDtos.DemandPatchRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.DemandResponse(success(http, in),
                demands.patch(DemandController.parseId(id), in.tenantId(), userId(in), body.data()));
    }

    @PostMapping(DEMANDS + "/{id}/freeze")
    public CanonicalDtos.DemandResponse freezeDemand(
            HttpServletRequest http, @PathVariable String id,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        Demand demand = demands.freeze(DemandController.parseId(id), in.tenantId(), userId(in));
        metrics.demandFrozen(in.tenantId());
        return new CanonicalDtos.DemandResponse(success(http, in), demand);
    }

    @PostMapping(DEMANDS + "/{id}/cancel")
    public CanonicalDtos.DemandResponse cancelDemand(
            HttpServletRequest http, @PathVariable String id,
            @Valid @RequestBody CanonicalDtos.DemandCancelRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        Demand demand = demands.cancel(DemandController.parseId(id), in.tenantId(), userId(in), body.data());
        metrics.demandCancelled(in.tenantId());
        return new CanonicalDtos.DemandResponse(success(http, in), demand);
    }

    // ── Bills ───────────────────────────────────────────────────────────────────

    @PostMapping(BILLS + "/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public CanonicalDtos.BillResponse generateBill(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.GenerateBillRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        Bill bill = bills.generate(body.data(), in.tenantId(), userId(in));
        metrics.billGenerated(in.tenantId(), body.data().businessServiceCode());
        return new CanonicalDtos.BillResponse(success(http, in), bill);
    }

    @GetMapping(BILLS)
    public CanonicalDtos.BillsResponse searchBills(
            HttpServletRequest http,
            @RequestParam(required = false)
            @Pattern(regexp = BusinessServiceRequests.CODE_PATTERN) String businessServiceCode,
            @RequestParam(required = false) String consumerCodes,
            @RequestParam(required = false) String billNumbers,
            @RequestParam(required = false) String billIds,
            @RequestParam(required = false) BillStatus status,
            @RequestParam(required = false) @Pattern(regexp = BillRequests.E164_PATTERN) String mobileNumber,
            @RequestParam(required = false) @Email String email,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        List<Bill> found = bills.search(new BillRequests.Filters(businessServiceCode,
                BillController.parseCsv(consumerCodes), BillController.parseCsv(billNumbers),
                BillController.parseUuidCsv(billIds), status, mobileNumber, email, limit, offset),
                in.tenantId());
        return new CanonicalDtos.BillsResponse(success(http, in), found);
    }

    @PostMapping(BILLS + "/cancel")
    public CanonicalDtos.BillResponse cancelBill(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.CancelBillRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        Bill bill = bills.cancel(body.data(), in.tenantId(), userId(in));
        metrics.billCancelled(in.tenantId(), body.data().businessServiceCode());
        return new CanonicalDtos.BillResponse(success(http, in), bill);
    }

    @PostMapping(BILLS + "/bulk-generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CanonicalDtos.BulkBillResponse bulkGenerateBills(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.BulkBillRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.BulkBillResponse(success(http, in),
                bills.bulkGenerate(body.data(), in.tenantId(), userId(in)));
    }

    // ── Payments ────────────────────────────────────────────────────────────────

    @PostMapping(PAYMENTS)
    @ResponseStatus(HttpStatus.CREATED)
    public CanonicalDtos.PaymentResponse createPayment(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.PaymentCreateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        Payment payment = payments.create(body.data(), in.tenantId(), userId(in));
        metrics.paymentCreated(in.tenantId());
        return new CanonicalDtos.PaymentResponse(success(http, in), payment);
    }

    @PostMapping(PAYMENTS + "/validate")
    public CanonicalDtos.PaymentResponse validatePayment(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.PaymentCreateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.PaymentResponse(success(http, in),
                payments.validate(body.data(), in.tenantId(), userId(in)));
    }

    @GetMapping(PAYMENTS)
    public CanonicalDtos.PaymentsResponse searchPayments(
            HttpServletRequest http,
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
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        org.digit.billing.model.PaymentRequests.Filters filters =
                new org.digit.billing.model.PaymentRequests.Filters(
                        BillController.parseUuidCsv(paymentIds),
                        BillController.parseUuidCsv(billIds),
                        BillController.parseCsv(receiptNumbers),
                        BillController.parseCsv(consumerCodes),
                        PaymentController.parseEnumCsv(paymentStatuses, PaymentStatus.class),
                        PaymentController.parseEnumCsv(instrumentStatuses, InstrumentStatus.class),
                        PaymentController.parseEnumCsv(paymentModes, PaymentMode.class),
                        BillController.parseCsv(payerIds),
                        businessServiceCode, transactionNumber, payerMobileNumber,
                        fromDate, toDate, limit, offset);
        return new CanonicalDtos.PaymentsResponse(success(http, in), payments.search(filters, in.tenantId()));
    }

    @GetMapping(PAYMENTS + "/{id}")
    public CanonicalDtos.PaymentResponse getPayment(
            HttpServletRequest http, @PathVariable String id,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.PaymentResponse(success(http, in),
                payments.getById(PaymentController.parseId(id), in.tenantId()));
    }

    // ── Shared helpers (also used by CanonicalTenantFilter and the advice) ───────

    /**
     * Wraps what {@link BulkResponses} decided. Those answers are RETURNED rather than
     * thrown, so they never reach an exception advice: without this the all-failed 400/422
     * of a bulk route would be the one canonical response carrying a bare error array.
     */
    private ResponseEntity<Object> bulk(HttpServletRequest http, CanonicalDtos.RequestMetadata in,
                                        ResponseEntity<?> answer) {
        Object payload = answer.getBody();
        if (answer.getStatusCode().isSameCodeAs(HttpStatus.MULTI_STATUS)) {
            return ResponseEntity.status(answer.getStatusCode()).body(new CanonicalDtos.DemandBulkResponse(
                    success(http, in), (DemandRequests.BulkResponse) payload));
        }
        if (answer.getStatusCode().is2xxSuccessful()) {
            @SuppressWarnings("unchecked")
            List<Demand> created = (List<Demand>) payload;
            return ResponseEntity.status(answer.getStatusCode())
                    .body(new CanonicalDtos.DemandsResponse(success(http, in), created));
        }
        @SuppressWarnings("unchecked")
        List<Error> errors = (List<Error>) payload;
        return ResponseEntity.status(answer.getStatusCode()).body(new CanonicalDtos.ErrorResponse(
                responseMetadata(http, in, CanonicalDtos.Status.FAILED), errors));
    }

    /**
     * The check {@link HeaderInterceptor} performs on X-User-ID, moved to the body. Called
     * only from the operations it demands the header for — every non-GET route — so a
     * canonical GET stays as anonymous as its 3.0 twin. Same error code as the header path;
     * params name the field, not the header.
     */
    static String userId(CanonicalDtos.RequestMetadata in) {
        Object value = in.userInfo() == null ? null : in.userInfo().get(USER_ID);
        String userId = value == null ? null : value.toString().trim();
        if (userId == null || userId.isEmpty()) {
            throw new CustomException(ErrorCodes.MISSING_HEADER, "Missing required field", null,
                    List.of("requestMetadata.userInfo." + USER_ID), HttpStatus.BAD_REQUEST);
        }
        return userId;
    }

    static CanonicalDtos.ResponseMetadata success(HttpServletRequest http, CanonicalDtos.RequestMetadata in) {
        return responseMetadata(http, in, CanonicalDtos.Status.SUCCESSFUL);
    }

    /**
     * {@code in} is null on the error path — the request may have failed before or during
     * body binding, so the echo falls back to whatever the filter managed to parse.
     */
    static CanonicalDtos.ResponseMetadata responseMetadata(
            HttpServletRequest http, CanonicalDtos.RequestMetadata in, CanonicalDtos.Status status) {
        long now = System.currentTimeMillis();
        CanonicalDtos.RequestMetadata source = in != null ? in : CanonicalTenantFilter.requestMetadata(http);
        return new CanonicalDtos.ResponseMetadata(
                now,
                CanonicalTenantFilter.elapsed(http, now),
                source == null ? null : source.msgId(),
                source == null ? null : source.requestId(),
                source == null ? null : source.correlationId(),
                status);
    }
}
