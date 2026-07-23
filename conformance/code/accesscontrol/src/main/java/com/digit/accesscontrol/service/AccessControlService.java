package com.digit.accesscontrol.service;

import com.digit.accesscontrol.model.CreateJbacRuleRequest;
import com.digit.accesscontrol.model.CreateRbacRuleRequest;
import com.digit.accesscontrol.model.Filters;
import com.digit.accesscontrol.model.JbacRule;
import com.digit.accesscontrol.model.Responses;
import com.digit.accesscontrol.model.Rule;
import com.digit.accesscontrol.model.UpdateJbacRuleRequest;
import com.digit.accesscontrol.model.UpdateRbacRuleRequest;
import com.digit.accesscontrol.repository.JbacRepository;
import com.digit.accesscontrol.repository.RbacRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer. Mirrors the Go service: a thin pass-through to the repositories (the Go service
 * adds only debug logging). Holds no business logic of its own beyond response assembly.
 */
@Service
public class AccessControlService {

    private final RbacRepository rbacRepo;
    private final JbacRepository jbacRepo;

    public AccessControlService(RbacRepository rbacRepo, JbacRepository jbacRepo) {
        this.rbacRepo = rbacRepo;
        this.jbacRepo = jbacRepo;
    }

    // ---------- RBAC ----------

    public Rule createRbacRule(String tenantId, CreateRbacRuleRequest req, String userId, String requestId) {
        return rbacRepo.create(tenantId, req, userId, requestId);
    }

    public Rule getRbacRule(String tenantId, String id) {
        return rbacRepo.get(tenantId, id);
    }

    public Rule updateRbacRule(String tenantId, String id, UpdateRbacRuleRequest req, String userId, String requestId) {
        return rbacRepo.update(tenantId, id, req, userId, requestId);
    }

    public void deleteRbacRule(String tenantId, String id) {
        rbacRepo.delete(tenantId, id);
    }

    public Responses.RbacRuleListResponse listRbacRules(String tenantId, Filters.RbacRulesFilter f) {
        RbacRepository.ListResult res = rbacRepo.list(tenantId, f);
        return new Responses.RbacRuleListResponse(res.rules, f.limit, f.offset, res.total);
    }

    public Responses.RbacRuleListResponse listAllRbacRules(Filters.AllRulesFilter f) {
        RbacRepository.ListResult res = rbacRepo.listAll(f);
        return new Responses.RbacRuleListResponse(res.rules, f.limit, f.offset, res.total);
    }

    public String getAllRbacRulesVersion() {
        return rbacRepo.versionHash();
    }

    public Responses.BulkCreateRbacRulesResponse bulkCreateRbacRules(String tenantId,
            List<CreateRbacRuleRequest> rules, String userId, String requestId) {
        int created = rbacRepo.bulkCreate(tenantId, rules, userId, requestId);
        return new Responses.BulkCreateRbacRulesResponse(created);
    }

    public void deleteRbacRulesByTenant(String tenantId) {
        rbacRepo.deleteByTenant(tenantId);
    }

    // ---------- JBAC ----------

    public JbacRule createJbacRule(String tenantId, CreateJbacRuleRequest req, String userId, String requestId) {
        return jbacRepo.create(tenantId, req, userId, requestId);
    }

    public JbacRule getJbacRule(String tenantId, String id) {
        return jbacRepo.get(tenantId, id);
    }

    public JbacRule updateJbacRule(String tenantId, String id, UpdateJbacRuleRequest req, String userId, String requestId) {
        return jbacRepo.update(tenantId, id, req, userId, requestId);
    }

    public void deleteJbacRule(String tenantId, String id) {
        jbacRepo.delete(tenantId, id);
    }

    public Responses.JbacRuleListResponse listJbacRules(String tenantId, Filters.JbacRulesFilter f) {
        JbacRepository.ListResult res = jbacRepo.list(tenantId, f);
        return new Responses.JbacRuleListResponse(res.rules, f.limit, f.offset, res.total);
    }

    public Responses.JbacRuleListResponse listAllJbacRules(Filters.AllRulesFilter f) {
        JbacRepository.ListResult res = jbacRepo.listAll(f);
        return new Responses.JbacRuleListResponse(res.rules, f.limit, f.offset, res.total);
    }

    public String getAllJbacRulesVersion() {
        return jbacRepo.versionHash();
    }

    public Responses.BulkCreateJbacRulesResponse bulkCreateJbacRules(String tenantId,
            List<CreateJbacRuleRequest> rules, String userId, String requestId) {
        int created = jbacRepo.bulkCreate(tenantId, rules, userId, requestId);
        return new Responses.BulkCreateJbacRulesResponse(created);
    }

    public void deleteJbacRulesByTenant(String tenantId) {
        jbacRepo.deleteByTenant(tenantId);
    }
}
