package com.digit.accesscontrol.web;

import com.digit.accesscontrol.constants.ErrorCodes;
import com.digit.accesscontrol.constants.Headers;
import com.digit.accesscontrol.constants.Constants;
import com.digit.accesscontrol.model.CreateRbacRuleRequest;
import com.digit.accesscontrol.model.Filters;
import com.digit.accesscontrol.model.Responses;
import com.digit.accesscontrol.model.Rule;
import com.digit.accesscontrol.model.UpdateRbacRuleRequest;
import com.digit.accesscontrol.service.AccessControlService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** RBAC rule endpoints. Mirrors Go routes group /v3/rbac/rules + rbac_handlers.go. */
@RestController
@RequestMapping("${accesscontrol.server.context-path:/access}/v3/rbac/rules")
public class RbacController {

    private final AccessControlService svc;
    private final ObjectMapper objectMapper;

    public RbacController(AccessControlService svc, ObjectMapper objectMapper) {
        this.svc = svc;
        this.objectMapper = objectMapper;
    }

    // POST /v3/rbac/rules/  — create (requires X-Tenant-ID, X-User-ID)
    @PostMapping("/")
    public ResponseEntity<?> create(HttpServletRequest request,
                                    @RequestBody(required = false) byte[] body) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.requireUserId(request);

        CreateRbacRuleRequest req = ControllerSupport.parseBody(objectMapper, body, CreateRbacRuleRequest.class);
        req.applyDefaults();
        ControllerSupport.failIfValidationErrors(com.digit.accesscontrol.web.Validators.validateRbacCreate(req));

        Rule rule = svc.createRbacRule(tenantId, req,
                ControllerSupport.header(request, Headers.USER_ID),
                ControllerSupport.header(request, Headers.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Responses.RbacRuleResponse(rule));
    }

    // GET /v3/rbac/rules/{id}/
    @GetMapping("/{id}/")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable String id) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.failIfValidationErrors(Validators.validateRuleID(id));
        try {
            Rule rule = svc.getRbacRule(tenantId, id);
            return ResponseEntity.ok(new Responses.RbacRuleResponse(rule));
        } catch (CustomException e) {
            throw remapNotFound(e);
        }
    }

    // PATCH /v3/rbac/rules/{id}/  (requires X-User-ID)
    @PatchMapping("/{id}/")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable String id,
                                    @RequestBody(required = false) byte[] body) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.requireUserId(request);

        ControllerSupport.failIfValidationErrors(Validators.validateRuleID(id));

        JsonNode tree = ControllerSupport.parseTree(objectMapper, body);
        ControllerSupport.failIfValidationErrors(
                ControllerSupport.rejectExplicitNulls(tree, UpdateRbacRuleRequest.NON_NULLABLE_FIELDS));
        UpdateRbacRuleRequest req = UpdateRequestMapper.rbac(objectMapper, tree);
        ControllerSupport.failIfValidationErrors(Validators.validateRbacUpdate(req));

        try {
            Rule rule = svc.updateRbacRule(tenantId, id, req,
                    ControllerSupport.header(request, Headers.USER_ID),
                    ControllerSupport.header(request, Headers.REQUEST_ID));
            return ResponseEntity.ok(new Responses.RbacRuleResponse(rule));
        } catch (CustomException e) {
            throw remapNotFound(e);
        }
    }

    // DELETE /v3/rbac/rules/{id}/
    @DeleteMapping("/{id}/")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable String id) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.failIfValidationErrors(Validators.validateRuleID(id));
        try {
            svc.deleteRbacRule(tenantId, id);
            return ResponseEntity.noContent().build();
        } catch (CustomException e) {
            throw remapNotFound(e);
        }
    }

    // GET /v3/rbac/rules/
    @GetMapping("/")
    public ResponseEntity<?> list(HttpServletRequest request,
                                  @RequestParam(value = "roleName", required = false) String roleName,
                                  @RequestParam(value = "httpMethod", required = false) String httpMethod,
                                  @RequestParam(value = "effect", required = false) String effect,
                                  @RequestParam(value = "enabled", required = false) String enabled,
                                  @RequestParam(value = "limit", required = false) String limit,
                                  @RequestParam(value = "offset", required = false) String offset) {
        String tenantId = ControllerSupport.requireTenantId(request);

        Filters.RbacRulesFilter f = new Filters.RbacRulesFilter();
        f.roleName = roleName;
        f.httpMethod = httpMethod;
        f.effect = effect;
        // gin ShouldBindQuery: bind all fields (struct order: enabled, limit, offset) before
        // validating any. Enabled precedes Limit/Offset, so its parse error wins over range errors.
        f.enabled = ControllerSupport.bindBool(enabled);
        long limitV = ControllerSupport.bindInt(limit);
        long offsetV = ControllerSupport.bindInt(offset);
        ControllerSupport.validateRange(limitV, 0, 100, "RbacRulesFilter", "Limit");
        ControllerSupport.validateRange(offsetV, 0, 10000, "RbacRulesFilter", "Offset");
        f.limit = (int) limitV;
        f.offset = (int) offsetV;
        if (f.limit == 0) {
            f.limit = 50;
        }
        return ResponseEntity.ok(svc.listRbacRules(tenantId, f));
    }

    // POST /v3/rbac/rules/bulk  (requires X-User-ID)
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkCreate(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.requireUserId(request);

        Responses.BulkCreateRbacRulesRequest req =
                ControllerSupport.parseBody(objectMapper, body, Responses.BulkCreateRbacRulesRequest.class);

        List<CreateRbacRuleRequest> rules = req.getRules();
        if (rules == null || rules.isEmpty()) {
            throw ControllerSupport.invalidRequest("rules must be a non-empty array");
        }
        if (rules.size() > Constants.MAX_BULK_RULES_PER_REQUEST) {
            throw ControllerSupport.invalidRequest(String.format(
                    "rules must contain at most %d entries (got %d)",
                    Constants.MAX_BULK_RULES_PER_REQUEST, rules.size()));
        }
        for (int i = 0; i < rules.size(); i++) {
            CreateRbacRuleRequest r = rules.get(i);
            r.applyDefaults();
            List<String> errs = Validators.validateRbacCreate(r);
            if (!errs.isEmpty()) {
                List<String> indexed = new ArrayList<>(errs.size());
                for (String e : errs) {
                    indexed.add(String.format("rules[%d]: %s", i, e));
                }
                ControllerSupport.failIfValidationErrors(indexed);
            }
        }
        Responses.BulkCreateRbacRulesResponse resp = svc.bulkCreateRbacRules(tenantId, rules,
                ControllerSupport.header(request, Headers.USER_ID),
                ControllerSupport.header(request, Headers.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    // DELETE /v3/rbac/rules/tenant
    @DeleteMapping("/tenant")
    public ResponseEntity<?> deleteByTenant(HttpServletRequest request) {
        String tenantId = ControllerSupport.requireTenantId(request);
        svc.deleteRbacRulesByTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    /** Remaps the repository's NOT_FOUND sentinel to the endpoint-specific not-found message;
     *  any other CustomException (e.g. validation) is rethrown unchanged. */
    private static CustomException remapNotFound(CustomException e) {
        if (ErrorCodes.NOT_FOUND.equals(e.getCode())) {
            return new CustomException(ErrorCodes.NOT_FOUND,
                    "No RBAC rule found with the specified ID for this tenant");
        }
        return e;
    }
}
