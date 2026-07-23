package com.digit.accesscontrol.web;

import com.digit.accesscontrol.model.Filters;
import com.digit.accesscontrol.service.AccessControlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal Kong-plugin endpoints. Mirrors Go routes group /v3/internal — no tenant/user header
 * requirements; lists are cross-tenant. Pagination: limit 0-1000, offset 0-100000, default limit 100.
 */
@RestController
@RequestMapping("${accesscontrol.server.context-path:/access}/v3/internal")
public class InternalController {

    private final AccessControlService svc;

    public InternalController(AccessControlService svc) {
        this.svc = svc;
    }

    @GetMapping("/rbac/rules")
    public ResponseEntity<?> listAllRbac(@RequestParam(value = "limit", required = false) String limit,
                                         @RequestParam(value = "offset", required = false) String offset) {
        Filters.AllRulesFilter f = allRulesFilter(limit, offset);
        return ResponseEntity.ok(svc.listAllRbacRules(f));
    }

    @GetMapping("/rbac/rules/version")
    public ResponseEntity<?> rbacVersion() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", svc.getAllRbacRulesVersion());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jbac/rules")
    public ResponseEntity<?> listAllJbac(@RequestParam(value = "limit", required = false) String limit,
                                         @RequestParam(value = "offset", required = false) String offset) {
        Filters.AllRulesFilter f = allRulesFilter(limit, offset);
        return ResponseEntity.ok(svc.listAllJbacRules(f));
    }

    @GetMapping("/jbac/rules/version")
    public ResponseEntity<?> jbacVersion() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", svc.getAllJbacRulesVersion());
        return ResponseEntity.ok(body);
    }

    private Filters.AllRulesFilter allRulesFilter(String limit, String offset) {
        Filters.AllRulesFilter f = new Filters.AllRulesFilter();
        // gin ShouldBindQuery: bind all fields (struct order) before validating any.
        long limitV = ControllerSupport.bindInt(limit);
        long offsetV = ControllerSupport.bindInt(offset);
        ControllerSupport.validateRange(limitV, 0, 1000, "AllRulesFilter", "Limit");
        ControllerSupport.validateRange(offsetV, 0, 100000, "AllRulesFilter", "Offset");
        f.limit = (int) limitV;
        f.offset = (int) offsetV;
        if (f.limit == 0) {
            f.limit = 100;
        }
        return f;
    }
}
