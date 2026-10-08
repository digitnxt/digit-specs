package org.digit.billing.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.digit.tracer.model.Error;

/**
 * Canonical (header-free) envelopes of billing-canonical.yml: the same payloads the 3.0
 * routes take, wrapped with the metadata their X-* headers carry.
 *
 * <p>Search, get, delete and freeze carry no payload of their own — their criteria stay
 * query or path parameters — so they take {@link MetadataOnlyRequest}.
 */
public final class CanonicalDtos {

    public enum Status {
        SUCCESSFUL, FAILED
    }

    /**
     * userInfo is an opaque common schema (declared with no properties), so it is carried
     * as a map and read for the user id — see CanonicalController#userId. Every write
     * operation requires it, mirroring HeaderInterceptor's X-User-ID rule.
     */
    public record RequestMetadata(
            @NotNull @Min(1000000000000L) @Max(9999999999999L) Long ts,
            @Size(min = 2, max = 64) String msgId,
            @Size(min = 2, max = 64) String requestId,
            @Size(min = 2, max = 64) String correlationId,
            @NotBlank @Size(min = 2, max = 64) String tenantId,
            Map<String, Object> userInfo) {
    }

    /** Echo fields are omitted when the request did not carry them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResponseMetadata(
            long ts, Long responseTime, String msgId, String requestId,
            String correlationId, Status status) {
    }

    /** Search, get, delete and freeze: criteria stay query and path parameters. */
    public record MetadataOnlyRequest(@NotNull @Valid RequestMetadata requestMetadata) {
    }

    // ── Business services ───────────────────────────────────────────────────────

    public record BusinessServiceCreateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull List<BusinessServiceRequests.@Valid Create> data) {
    }

    public record BusinessServiceUpdateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid BusinessServiceRequests.Update data) {
    }

    public record BusinessServicePatchRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid BusinessServiceRequests.Patch data) {
    }

    public record BusinessServiceResponse(ResponseMetadata responseMetadata, BusinessService data) {
    }

    public record BusinessServicesResponse(ResponseMetadata responseMetadata, List<BusinessService> data) {
    }

    // ── Tax heads ───────────────────────────────────────────────────────────────

    public record TaxHeadCreateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull List<TaxHeadRequests.@Valid Create> data) {
    }

    public record TaxHeadUpdateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid TaxHeadRequests.Update data) {
    }

    public record TaxHeadPatchRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid TaxHeadRequests.Patch data) {
    }

    public record TaxHeadResponse(ResponseMetadata responseMetadata, TaxHead data) {
    }

    public record TaxHeadsResponse(ResponseMetadata responseMetadata, List<TaxHead> data) {
    }

    // ── Demands ─────────────────────────────────────────────────────────────────

    public record DemandCreateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull List<DemandRequests.@Valid Create> data) {
    }

    public record DemandUpdateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull List<DemandRequests.@Valid Update> data) {
    }

    public record DemandPatchRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid DemandRequests.Patch data) {
    }

    /** Cancel's payload is optional on the 3.0 route ({@code @RequestBody(required = false)}). */
    public record DemandCancelRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @Valid DemandRequests.CancelRequest data) {
    }

    public record DemandResponse(ResponseMetadata responseMetadata, Demand data) {
    }

    public record DemandsResponse(ResponseMetadata responseMetadata, List<Demand> data) {
    }

    /** The 207 of the bulk routes: the 3.0 BulkResponse, unchanged, under data. */
    public record DemandBulkResponse(ResponseMetadata responseMetadata, DemandRequests.BulkResponse data) {
    }

    // ── Bills ───────────────────────────────────────────────────────────────────

    public record GenerateBillRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid BillRequests.GenerateBillCriteria data) {
    }

    public record CancelBillRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid BillRequests.UpdateBillStatus data) {
    }

    public record BulkBillRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid BillRequests.BulkBillGenerator data) {
    }

    public record BillResponse(ResponseMetadata responseMetadata, Bill data) {
    }

    public record BillsResponse(ResponseMetadata responseMetadata, List<Bill> data) {
    }

    public record BulkBillResponse(ResponseMetadata responseMetadata, BillRequests.BulkBillResponse data) {
    }

    // ── Payments ────────────────────────────────────────────────────────────────

    public record PaymentCreateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid PaymentRequests.Create data) {
    }

    public record PaymentResponse(ResponseMetadata responseMetadata, Payment data) {
    }

    public record PaymentsResponse(ResponseMetadata responseMetadata, List<Payment> data) {
    }

    // ── Shared ──────────────────────────────────────────────────────────────────

    /** {@code {"deleted": true}} of the 3.0 delete routes, wrapped. */
    public record DeleteResponse(ResponseMetadata responseMetadata, Map<String, Boolean> data) {
    }

    /** Same tracer Error the 3.0 routes return as a bare array, wrapped. */
    public record ErrorResponse(ResponseMetadata responseMetadata, List<Error> errors) {
    }

    private CanonicalDtos() {
    }
}
