package org.digit.idgen.web;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.ErrorCodes;
import org.digit.idgen.service.TemplateService;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v3/template")
public class TemplateController {

    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.TemplateResponse create(
            @RequestHeader(HeaderInterceptor.TENANT_ID) String tenantId,
            @RequestHeader(HeaderInterceptor.USER_ID) String userId,
            @RequestHeader(value = HeaderInterceptor.REQUEST_ID, required = false) String requestId,
            @Valid @RequestBody Dtos.TemplateRequest request) {
        return service.create(tenantId, userId, emptyToNull(requestId), request);
    }

    @PutMapping
    public Dtos.TemplateResponse update(
            @RequestHeader(HeaderInterceptor.TENANT_ID) String tenantId,
            @RequestHeader(HeaderInterceptor.USER_ID) String userId,
            @RequestHeader(value = HeaderInterceptor.REQUEST_ID, required = false) String requestId,
            @Valid @RequestBody Dtos.TemplateRequest request) {
        return service.update(tenantId, userId, emptyToNull(requestId), request);
    }

    // Query params are validated in code, not by @Validated constraints — a
    // ConstraintViolationException has no branch in the tracer advice and would
    // surface as an "unhandled server exception" for what is client input.
    @GetMapping
    public List<Dtos.TemplateResponse> search(
            @RequestHeader(HeaderInterceptor.TENANT_ID) String tenantId,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "0") int offset) {
        validateTemplateCode(templateCode);
        if (limit != null && (limit < 1 || limit > 100)) {
            throw validation("field 'limit' must be between 1 and 100");
        }
        if (offset < 0) {
            throw validation("field 'offset' must be >= 0");
        }
        return service.search(tenantId, templateCode, version, parseIds(ids), limit, offset);
    }

    @DeleteMapping
    public Dtos.DeleteResponse delete(
            @RequestHeader(HeaderInterceptor.TENANT_ID) String tenantId,
            @RequestParam String templateCode,
            @RequestParam String version) {
        validateTemplateCode(templateCode);
        service.delete(tenantId, templateCode, version);
        return new Dtos.DeleteResponse(true);
    }

    static void validateTemplateCode(String templateCode) {
        if (templateCode != null && (templateCode.length() < 2 || templateCode.length() > 64)) {
            throw validation("field 'templateCode' length must be between 2 and 64");
        }
    }

    static CustomException validation(String message) {
        return new CustomException(ErrorCodes.VALIDATION_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    /** Comma-separated UUID list; any unparseable entry → 400 INVALID_PARAM (as in Go). */
    static List<UUID> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String s : raw.split(",")) {
            try {
                ids.add(UUID.fromString(s.trim()));
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCodes.INVALID_PARAM,
                        "invalid UUID in ids: " + s, HttpStatus.BAD_REQUEST);
            }
        }
        return ids;
    }

    static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
