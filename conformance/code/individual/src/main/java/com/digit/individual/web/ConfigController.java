package com.digit.individual.web;

import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.constants.Headers;
import com.digit.individual.model.Config;
import com.digit.individual.model.ConfigDTO;
import com.digit.individual.model.RequestContext;
import com.digit.individual.service.ConfigService;
import com.digit.individual.service.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.digit.tracer.model.CustomException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Tenant validation config resource. Mirrors Go internal/handlers/config_handler.go + routes.
 * Mounted under {@code <context-path>/v3/configs} (default /individuals/v3/configs).
 */
@RestController
@RequestMapping("${individual.server.context-path:/individuals}/v3/configs")
public class ConfigController {

    private final ConfigService service;
    private final RequestValidator validator;
    private final JsonMapper strictMapper;
    private final JsonMapper mapper;

    public ConfigController(ConfigService service, RequestValidator validator,
                            @Qualifier("strictJsonMapper") JsonMapper strictMapper,
                            JsonMapper mapper) {
        this.service = service;
        this.validator = validator;
        this.strictMapper = strictMapper;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<ConfigDTO> upsert(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = Headers.USER_ID, required = false) String userId,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        ConfigDTO dto;
        if (body == null || body.length == 0) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "Invalid request body: EOF");
        }
        try {
            dto = strictMapper.readValue(body, ConfigDTO.class);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "Invalid request body: " + e.getMessage());
        }

        // Reject empty body (matches Go bug.md #15).
        boolean empty = isBlank(dto.getMobileRegex()) && isBlank(dto.getNameRegex())
                && (dto.getUniquenessCriteria() == null || dto.getUniquenessCriteria().isEmpty());
        if (empty) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                    "at least one of mobileRegex, nameRegex, uniquenessCriteria is required");
        }

        Config entity = toEntity(dto);
        validator.validateConfig(entity);

        RequestContext rc = ctx(request, tenantId, userId);
        ConfigService.UpsertResult result = service.upsert(rc, entity);

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(toDto(result.config()));
    }

    @GetMapping
    public ResponseEntity<ConfigDTO> get(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId) {
        Config cfg = service.getByTenant(tenantId);
        if (cfg == null) {
            throw new CustomException(ErrorCodes.NON_EXISTENT_ENTITY, "No configuration found for this tenant",
                    HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(toDto(cfg));
    }

    // ----------------------------------------------------------- mapping

    private Config toEntity(ConfigDTO d) {
        Config e = new Config();
        e.setMobileRegex(d.getMobileRegex());
        e.setNameRegex(d.getNameRegex());
        if (d.getUniquenessCriteria() != null && !d.getUniquenessCriteria().isEmpty()) {
            try {
                e.setUniquenessCriteria(mapper.writeValueAsString(d.getUniquenessCriteria()));
            } catch (Exception ignore) {
                // leave null on serialization error (matches Go ConfigToEntity best-effort)
            }
        }
        return e;
    }

    private ConfigDTO toDto(Config e) {
        ConfigDTO d = new ConfigDTO();
        d.setMobileRegex(e.getMobileRegex());
        d.setNameRegex(e.getNameRegex());
        d.setVersion(e.getVersion());
        d.setRequestId(e.getRequestId());
        d.setAuditDetail(com.digit.individual.model.AuditDetail.of(
                e.getCreatedBy(), e.getModifiedBy(), e.getCreatedTime(), e.getModifiedTime()));
        if (e.getUniquenessCriteria() != null && !e.getUniquenessCriteria().isEmpty()) {
            try {
                List<?> raw = mapper.readValue(e.getUniquenessCriteria(), List.class);
                java.util.List<String> out = new java.util.ArrayList<>();
                for (Object o : raw) {
                    if (o instanceof String s) {
                        out.add(s);
                    }
                }
                if (!out.isEmpty()) {
                    d.setUniquenessCriteria(out);
                }
            } catch (Exception ignore) {
                // leave null (matches Go ConfigFromEntity best-effort)
            }
        }
        return d;
    }

    private RequestContext ctx(HttpServletRequest req, String tenantId, String userId) {
        Object rid = req.getAttribute(ForwardHeadersFilter.REQUEST_ID_ATTR);
        return new RequestContext(tenantId, userId, rid == null ? null : rid.toString());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
