package org.digit.idgen.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.digit.idgen.model.CanonicalDtos;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.ErrorCodes;
import org.digit.idgen.service.GenerationService;
import org.digit.idgen.service.TemplateService;
import org.digit.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical (header-free) routes of idgen-canonical.yml. Pure adapter: every request datum
 * the 3.0 routes take as an X-* header is read out of {@code requestMetadata} instead, then
 * the same {@link TemplateService}/{@link GenerationService} methods run.
 *
 * <p>Query parameters stay query parameters — they are filters, not request metadata — so
 * search and delete carry only {@code requestMetadata} in the body and reuse
 * {@link TemplateController}'s own parameter validation and id parsing rather than
 * repeating it.
 *
 * <p>Tenant is promoted back to X-Tenant-ID by {@link CanonicalTenantFilter} before the
 * request reaches here, so tenant-migration's transaction and search_path work unchanged.
 * User id is NOT promoted — it is validated here so the error names the body field the
 * caller actually got wrong rather than a header they never sent.
 */
@RestController
public class CanonicalController {

    /**
     * Configurable path segment, {@code canonical} by default. A compile-time constant so
     * it drives both the mappings below and {@code @Value} in {@link WebConfig} — the
     * interceptor's exclude patterns are not placeholder-resolved.
     */
    public static final String PATH = "${idgen.canonical-path:canonical}";

    private static final String TEMPLATE = "/v3/" + PATH + "/template";
    private static final String GENERATE = "/v3/" + PATH + "/generate";
    private static final String GENERATE_BULK = "/v3/" + PATH + "/generate/bulk";

    /** Key read out of the opaque userInfo map. */
    static final String USER_ID = "userId";

    private final TemplateService templates;
    private final GenerationService generation;

    public CanonicalController(TemplateService templates, GenerationService generation) {
        this.templates = templates;
        this.generation = generation;
    }

    // ── Template management ─────────────────────────────────────────────────────

    @PostMapping(TEMPLATE)
    @ResponseStatus(HttpStatus.CREATED)
    public CanonicalDtos.TemplateResponse create(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.TemplateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.TemplateResponse(success(http, in), templates.create(
                in.tenantId(), userId(in), TemplateController.emptyToNull(in.requestId()), body.data()));
    }

    @PutMapping(TEMPLATE)
    public CanonicalDtos.TemplateResponse update(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.TemplateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.TemplateResponse(success(http, in), templates.update(
                in.tenantId(), userId(in), TemplateController.emptyToNull(in.requestId()), body.data()));
    }

    /**
     * Search reads no user id — {@link TemplateService#search} does not audit one. The
     * query parameters and their validation are the 3.0 route's, unchanged.
     */
    @GetMapping(TEMPLATE)
    public CanonicalDtos.TemplateSearchResponse search(
            HttpServletRequest http,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "0") int offset,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        TemplateController.validateTemplateCode(templateCode);
        if (limit != null && (limit < 1 || limit > 100)) {
            throw TemplateController.validation("field 'limit' must be between 1 and 100");
        }
        if (offset < 0) {
            throw TemplateController.validation("field 'offset' must be >= 0");
        }
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        List<Dtos.TemplateResponse> found = templates.search(
                in.tenantId(), templateCode, version, TemplateController.parseIds(ids), limit, offset);
        return new CanonicalDtos.TemplateSearchResponse(success(http, in), found);
    }

    /**
     * Requires the user id even though {@link TemplateService#delete} does not take one:
     * the 3.0 route demands an X-User-ID header here, and both routes are kept to the same
     * contract so a caller migrating between them meets no surprise.
     */
    @DeleteMapping(TEMPLATE)
    public CanonicalDtos.DeleteTemplateResponse delete(
            HttpServletRequest http,
            @RequestParam String templateCode,
            @RequestParam String version,
            @Valid @RequestBody CanonicalDtos.MetadataOnlyRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        userId(in);
        TemplateController.validateTemplateCode(templateCode);
        templates.delete(in.tenantId(), templateCode, version);
        return new CanonicalDtos.DeleteTemplateResponse(success(http, in), new Dtos.DeleteResponse(true));
    }

    // ── ID generation ───────────────────────────────────────────────────────────

    /** Generation writes no audit record, so no user id is required (3.0 parity). */
    @PostMapping(GENERATE)
    public CanonicalDtos.GenerateResponse generate(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.GenerateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.GenerateResponse(success(http, in),
                generation.generate(in.tenantId(), body.data()));
    }

    @PostMapping(GENERATE_BULK)
    public CanonicalDtos.BulkGenerateResponse bulkGenerate(
            HttpServletRequest http, @Valid @RequestBody CanonicalDtos.BulkGenerateRequest body) {
        CanonicalDtos.RequestMetadata in = body.requestMetadata();
        return new CanonicalDtos.BulkGenerateResponse(success(http, in),
                generation.bulkGenerate(in.tenantId(), body.data()));
    }

    // ── Shared helpers (also used by CanonicalExceptionAdvice) ───────────────────

    /**
     * The check {@link HeaderInterceptor} performs on X-User-ID, moved to the body. Same
     * error code as the header path; params name the field, not the header.
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
