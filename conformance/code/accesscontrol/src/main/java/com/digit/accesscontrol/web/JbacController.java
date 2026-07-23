package com.digit.accesscontrol.web;

import com.digit.accesscontrol.constants.Constants;
import com.digit.accesscontrol.constants.ErrorCodes;
import com.digit.accesscontrol.constants.Headers;
import com.digit.accesscontrol.model.CreateJbacRuleRequest;
import com.digit.accesscontrol.model.Filters;
import com.digit.accesscontrol.model.JbacRule;
import com.digit.accesscontrol.model.Responses;
import com.digit.accesscontrol.model.UpdateJbacRuleRequest;
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

/** JBAC rule endpoints. Mirrors Go routes group /v3/jbac/rules + jbac_handlers.go. */
@RestController
@RequestMapping("${accesscontrol.server.context-path:/access}/v3/jbac/rules")
public class JbacController {

    private final AccessControlService svc;
    private final ObjectMapper objectMapper;

    public JbacController(AccessControlService svc, ObjectMapper objectMapper) {
        this.svc = svc;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/")
    public ResponseEntity<?> create(HttpServletRequest request,
                                    @RequestBody(required = false) byte[] body) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.requireUserId(request);

        CreateJbacRuleRequest req = ControllerSupport.parseBody(objectMapper, body, CreateJbacRuleRequest.class);
        ControllerSupport.failIfValidationErrors(Validators.validateJbacCreate(req));

        JbacRule rule = svc.createJbacRule(tenantId, req,
                ControllerSupport.header(request, Headers.USER_ID),
                ControllerSupport.header(request, Headers.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Responses.JbacRuleResponse(rule));
    }

    @GetMapping("/{id}/")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable String id) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.failIfValidationErrors(Validators.validateRuleID(id));
        try {
            JbacRule rule = svc.getJbacRule(tenantId, id);
            return ResponseEntity.ok(new Responses.JbacRuleResponse(rule));
        } catch (CustomException e) {
            throw remapNotFound(e);
        }
    }

    @PatchMapping("/{id}/")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable String id,
                                    @RequestBody(required = false) byte[] body) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.requireUserId(request);

        ControllerSupport.failIfValidationErrors(Validators.validateRuleID(id));

        JsonNode tree = ControllerSupport.parseTree(objectMapper, body);
        ControllerSupport.failIfValidationErrors(
                ControllerSupport.rejectExplicitNulls(tree, UpdateJbacRuleRequest.NON_NULLABLE_FIELDS));
        UpdateJbacRuleRequest req = UpdateRequestMapper.jbac(objectMapper, tree);
        ControllerSupport.failIfValidationErrors(Validators.validateJbacUpdate(req));

        try {
            JbacRule rule = svc.updateJbacRule(tenantId, id, req,
                    ControllerSupport.header(request, Headers.USER_ID),
                    ControllerSupport.header(request, Headers.REQUEST_ID));
            return ResponseEntity.ok(new Responses.JbacRuleResponse(rule));
        } catch (CustomException e) {
            throw remapNotFound(e);
        }
    }

    @DeleteMapping("/{id}/")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable String id) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.failIfValidationErrors(Validators.validateRuleID(id));
        try {
            svc.deleteJbacRule(tenantId, id);
            return ResponseEntity.noContent().build();
        } catch (CustomException e) {
            throw remapNotFound(e);
        }
    }

    @GetMapping("/")
    public ResponseEntity<?> list(HttpServletRequest request,
                                  @RequestParam(value = "name", required = false) String name,
                                  @RequestParam(value = "enforcement", required = false) String enforcement,
                                  @RequestParam(value = "limit", required = false) String limit,
                                  @RequestParam(value = "offset", required = false) String offset) {
        String tenantId = ControllerSupport.requireTenantId(request);

        Filters.JbacRulesFilter f = new Filters.JbacRulesFilter();
        f.name = name;
        f.enforcement = enforcement;
        // gin ShouldBindQuery: bind all fields (struct order) before validating any.
        long limitV = ControllerSupport.bindInt(limit);
        long offsetV = ControllerSupport.bindInt(offset);
        ControllerSupport.validateRange(limitV, 0, 100, "JbacRulesFilter", "Limit");
        ControllerSupport.validateRange(offsetV, 0, 10000, "JbacRulesFilter", "Offset");
        f.limit = (int) limitV;
        f.offset = (int) offsetV;
        if (f.limit == 0) {
            f.limit = 50;
        }
        return ResponseEntity.ok(svc.listJbacRules(tenantId, f));
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> bulkCreate(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String tenantId = ControllerSupport.requireTenantId(request);
        ControllerSupport.requireUserId(request);

        Responses.BulkCreateJbacRulesRequest req =
                ControllerSupport.parseBody(objectMapper, body, Responses.BulkCreateJbacRulesRequest.class);

        List<CreateJbacRuleRequest> rules = req.getRules();
        if (rules == null || rules.isEmpty()) {
            throw ControllerSupport.invalidRequest("rules must be a non-empty array");
        }
        if (rules.size() > Constants.MAX_BULK_RULES_PER_REQUEST) {
            throw ControllerSupport.invalidRequest(String.format(
                    "rules must contain at most %d entries (got %d)",
                    Constants.MAX_BULK_RULES_PER_REQUEST, rules.size()));
        }
        for (int i = 0; i < rules.size(); i++) {
            List<String> errs = Validators.validateJbacCreate(rules.get(i));
            if (!errs.isEmpty()) {
                List<String> indexed = new ArrayList<>(errs.size());
                for (String e : errs) {
                    indexed.add(String.format("rules[%d]: %s", i, e));
                }
                ControllerSupport.failIfValidationErrors(indexed);
            }
        }
        Responses.BulkCreateJbacRulesResponse resp = svc.bulkCreateJbacRules(tenantId, rules,
                ControllerSupport.header(request, Headers.USER_ID),
                ControllerSupport.header(request, Headers.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @DeleteMapping("/tenant")
    public ResponseEntity<?> deleteByTenant(HttpServletRequest request) {
        String tenantId = ControllerSupport.requireTenantId(request);
        svc.deleteJbacRulesByTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    /** Remaps the repository's NOT_FOUND sentinel to the endpoint-specific not-found message;
     *  any other CustomException (e.g. validation) is rethrown unchanged. */
    private static CustomException remapNotFound(CustomException e) {
        if (ErrorCodes.NOT_FOUND.equals(e.getCode())) {
            return new CustomException(ErrorCodes.NOT_FOUND,
                    "No JBAC rule found with the specified ID for this tenant");
        }
        return e;
    }
}
