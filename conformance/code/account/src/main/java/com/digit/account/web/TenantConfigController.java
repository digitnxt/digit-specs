package com.digit.account.web;

import com.digit.account.constants.Headers;
import com.digit.account.model.Mappers;
import com.digit.account.model.TenantConfigCreateRequest;
import com.digit.account.model.TenantConfigUpdateRequest;
import com.digit.account.service.TenantConfigService;
import com.digit.account.validator.TenantConfigValidator;
import com.digit.account.validator.TenantValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** Tenant configuration endpoints. Mirrors Go internal/handlers/tenant_config_handler.go.
 *  Business/validation errors surface as the tracer's CustomException (HTTP 400 via ExceptionAdvice);
 *  genuine infra failures propagate to the tracer's generic 500 handler. */
@RestController
@RequestMapping("/v3")
public class TenantConfigController {

    private final TenantConfigService service;
    private final ObjectMapper objectMapper;

    public TenantConfigController(TenantConfigService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** The owning tenant comes from X-Tenant-Id (tenant code); the body carries only key/value. */
    @PostMapping("/config")
    public ResponseEntity<?> createTenantConfig(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantCode,
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId,
            @RequestHeader(value = Headers.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) byte[] body) {
        ControllerSupport.failIfValidation(TenantConfigValidator.validateTenantCodeHeader(tenantCode));
        TenantConfigCreateRequest req = ControllerSupport.parseBody(objectMapper, body,
                TenantConfigCreateRequest.class);
        ControllerSupport.failIfValidation(TenantConfigValidator.validateTenantConfigEntity(
                Mappers.tenantConfigCreateRequestToEntity(req, tenantCode)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req, tenantCode, clientId, requestId));
    }

    @GetMapping("/config")
    public ResponseEntity<?> listTenantConfigs(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantCode,
            @RequestParam(value = "configKey", required = false) String configKey,
            @RequestParam(value = "isActive", required = false) String isActive,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size) {
        List<String> errs = new ArrayList<>();
        TenantConfigValidator.ListQueryParams p = TenantConfigValidator.validateTenantConfigListQuery(
                tenantCode, configKey, isActive, page, size, errs);
        ControllerSupport.failIfValidation(errs);
        return ResponseEntity.ok(service.list(p.tenantId, p.configKey, p.isActive, p.page, p.size));
    }

    @PutMapping("/config/{id}")
    public ResponseEntity<?> updateTenantConfig(
            @PathVariable("id") String id,
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId,
            @RequestHeader(value = Headers.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) byte[] body) {
        ControllerSupport.failIfValidation(TenantValidator.validateUUIDPath("config id", id));
        TenantConfigUpdateRequest req = ControllerSupport.parseBody(objectMapper, body,
                TenantConfigUpdateRequest.class);
        return ResponseEntity.ok(service.update(id, req, clientId, requestId));
    }
}
