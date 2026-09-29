package org.digit.idgen.model;

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
 * Canonical (header-free) envelopes of idgen-canonical.yml: the same payloads as
 * {@link Dtos}, wrapped with the metadata the X-* headers carry on the 3.0 routes.
 *
 * <p>The search and delete operations keep their query parameters — those are filters,
 * not request metadata — so their bodies carry {@link MetadataOnlyRequest}.
 */
public final class CanonicalDtos {

    public enum Status {
        SUCCESSFUL, FAILED
    }

    /**
     * userInfo is an opaque common schema (declared with no properties), so it is carried
     * as a map and read for the user id — see CanonicalController#userId.
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

    public record TemplateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid Dtos.TemplateRequest data) {
    }

    /** Search and delete carry metadata only; their criteria stay query parameters. */
    public record MetadataOnlyRequest(@NotNull @Valid RequestMetadata requestMetadata) {
    }

    public record TemplateResponse(ResponseMetadata responseMetadata, Dtos.TemplateResponse data) {
    }

    public record TemplateSearchResponse(
            ResponseMetadata responseMetadata, List<Dtos.TemplateResponse> data) {
    }

    public record DeleteTemplateResponse(ResponseMetadata responseMetadata, Dtos.DeleteResponse data) {
    }

    public record GenerateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid Dtos.GenerateRequest data) {
    }

    public record GenerateResponse(ResponseMetadata responseMetadata, Dtos.GenerateResponse data) {
    }

    public record BulkGenerateRequest(
            @NotNull @Valid RequestMetadata requestMetadata,
            @NotNull @Valid Dtos.BulkGenerateRequest data) {
    }

    public record BulkGenerateResponse(
            ResponseMetadata responseMetadata, Dtos.BulkGenerateResponse data) {
    }

    /** Same tracer Error the 3.0 routes return as a bare array, wrapped. */
    public record ErrorResponse(ResponseMetadata responseMetadata, List<Error> errors) {
    }

    private CanonicalDtos() {
    }
}
