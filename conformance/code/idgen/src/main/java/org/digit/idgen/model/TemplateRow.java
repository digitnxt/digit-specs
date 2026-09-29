package org.digit.idgen.model;

import java.util.UUID;

/** One row of {@code idgen_templates}. */
public record TemplateRow(
        UUID id,
        String tenantId,
        String templateCode,
        int version,
        TemplateConfig config,
        long createdTime,
        String createdBy,
        long modifiedTime,
        String modifiedBy,
        String requestId) {

    public String versionString() {
        return "v" + version;
    }

    /**
     * Copy with config defaults applied — for generation on legacy/hand-inserted rows
     * whose JSONB lacks nested fields (a Go zero-value would have absorbed those;
     * Java Integer/String nulls would NPE). Never used for search responses, which
     * return the stored config verbatim.
     */
    public TemplateRow withNormalizedConfig() {
        return new TemplateRow(id, tenantId, templateCode, version, config.normalized(),
                createdTime, createdBy, modifiedTime, modifiedBy, requestId);
    }

    public Dtos.TemplateResponse toResponse() {
        return new Dtos.TemplateResponse(id, templateCode, versionString(), config,
                new Dtos.AuditDetail(createdBy, createdTime, modifiedBy, modifiedTime));
    }
}
