package org.digit.idgen.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response records of the idgen API (shapes match the Go service 1:1). */
public final class Dtos {

    public record TemplateRequest(
            @NotBlank @Size(min = 2, max = 64) String templateCode,
            @NotNull @Valid TemplateConfig config) {
    }

    public record AuditDetail(String createdBy, long createdTime, String modifiedBy, long modifiedTime) {
    }

    public record TemplateResponse(
            UUID id, String templateCode, String version, TemplateConfig config, AuditDetail auditDetail) {
    }

    public record DeleteResponse(boolean deleted) {
    }

    public record GenerateRequest(
            @NotBlank @Size(min = 2, max = 64) String templateCode,
            Map<String, String> variables) {
    }

    public record GenerateResponse(String templateCode, String version, String id) {
    }

    public record BulkGenerateRequest(
            @NotBlank @Size(min = 2, max = 64) String templateCode,
            @NotNull @Min(1) @Max(1000) Integer count,
            Map<String, String> variables) {
    }

    public record BulkGenerateResponse(String templateCode, String version, int count, List<String> ids) {
    }

    private Dtos() {
    }
}
