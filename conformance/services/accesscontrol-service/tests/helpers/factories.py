"""
Test data factories for the access control service.

All factories produce unique data per call via uuid-based suffixes so
tests running in the same tenant do not collide.
"""

import uuid

VALID_HTTP_METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"]
VALID_EFFECTS = ["ALLOW", "DENY"]
VALID_JBAC_METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH"]


def _uid():
    return uuid.uuid4().hex[:8].upper()


def make_rbac_rule_request(**overrides):
    """Valid RbacRuleRequest body."""
    base = {
        "roleNames": [f"conformance-role-{_uid().lower()}"],
        "httpMethod": "GET",
        "path": f"/api/conformance/{_uid().lower()}",
        "effect": "ALLOW",
        "priority": 100,
        "enabled": True,
        "description": f"Conformance test RBAC rule {_uid()}",
    }
    return {**base, **overrides}


def make_update_rbac_rule_request(**overrides):
    """Valid UpdateRbacRuleRequest body (same required fields as create)."""
    base = {
        "roleNames": [f"conformance-role-updated-{_uid().lower()}"],
        "httpMethod": "POST",
        "path": f"/api/conformance/updated/{_uid().lower()}",
        "effect": "DENY",
        "priority": 200,
        "enabled": False,
        "description": f"Conformance updated RBAC rule {_uid()}",
    }
    return {**base, **overrides}


def make_jbac_rule_request(**overrides):
    """Valid JbacRuleRequest body."""
    base = {
        "name": f"conformance-jbac-{_uid().lower()}",
        "pathPattern": f"/api/conformance/{_uid().lower()}/*",
        "methods": ["GET"],
        "enforcement": "REQUIRED",
        "parentImpliesChildren": False,
        "description": f"Conformance JBAC test rule {_uid()}",
    }
    return {**base, **overrides}


def make_update_jbac_rule_request(**overrides):
    """Valid UpdateJbacRuleRequest body."""
    base = {
        "name": f"conformance-jbac-updated-{_uid().lower()}",
        "pathPattern": f"/api/conformance/updated/{_uid().lower()}/*",
        "methods": ["GET", "POST"],
        "enforcement": "OPTIONAL",
        "parentImpliesChildren": True,
        "description": f"Conformance updated JBAC rule {_uid()}",
    }
    return {**base, **overrides}


def make_bulk_rbac_request(count=2):
    """Valid bulk create RBAC rules request body."""
    return {"rules": [make_rbac_rule_request() for _ in range(count)]}


def make_bulk_jbac_request(count=2):
    """Valid bulk create JBAC rules request body."""
    return {"rules": [make_jbac_rule_request() for _ in range(count)]}


# ── Invalid RBAC payloads ──────────────────────────────────────────────────

def make_invalid_rbac_request(strategy="missing_role_names"):
    strategies = {
        "missing_role_names": {
            "httpMethod": "GET", "path": "/api/test",
            "effect": "ALLOW", "priority": 10,
        },
        "missing_http_method": {
            "roleNames": ["admin"], "path": "/api/test",
            "effect": "ALLOW", "priority": 10,
        },
        "missing_path": {
            "roleNames": ["admin"], "httpMethod": "GET",
            "effect": "ALLOW", "priority": 10,
        },
        "missing_effect": {
            "roleNames": ["admin"], "httpMethod": "GET",
            "path": "/api/test", "priority": 10,
        },
        "missing_priority": {
            "roleNames": ["admin"], "httpMethod": "GET",
            "path": "/api/test", "effect": "ALLOW",
        },
        "invalid_effect": {
            "roleNames": ["admin"], "httpMethod": "GET",
            "path": "/api/test", "effect": "GRANT", "priority": 10,
        },
        "invalid_http_method": {
            "roleNames": ["admin"], "httpMethod": "CONNECT",
            "path": "/api/test", "effect": "ALLOW", "priority": 10,
        },
        "empty_role_names": {
            "roleNames": [], "httpMethod": "GET",
            "path": "/api/test", "effect": "ALLOW", "priority": 10,
        },
        "priority_out_of_range": {
            "roleNames": ["admin"], "httpMethod": "GET",
            "path": "/api/test", "effect": "ALLOW", "priority": 2147483648,
        },
        "wrong_types": {
            "roleNames": "admin", "httpMethod": 123,
            "path": "/api/test", "effect": "ALLOW", "priority": "ten",
        },
    }
    return strategies.get(strategy, {})


def make_invalid_bulk_rbac_request(strategy="empty_rules"):
    strategies = {
        "empty_rules": {"rules": []},
        "missing_rules": {},
        "too_many_rules": {"rules": [make_rbac_rule_request() for _ in range(101)]},
        "invalid_rule_in_array": {
            "rules": [make_rbac_rule_request(), make_invalid_rbac_request("missing_effect")],
        },
    }
    return strategies.get(strategy, {})


# ── Invalid JBAC payloads ──────────────────────────────────────────────────

def make_invalid_jbac_request(strategy="missing_name"):
    strategies = {
        "missing_name": {
            "pathPattern": "/api/test", "methods": ["GET"],
            "enforcement": "REQUIRED", "parentImpliesChildren": False,
        },
        "missing_path_pattern": {
            "name": "Test Rule", "methods": ["GET"],
            "enforcement": "REQUIRED", "parentImpliesChildren": False,
        },
        "missing_methods": {
            "name": "Test Rule", "pathPattern": "/api/test",
            "enforcement": "REQUIRED", "parentImpliesChildren": False,
        },
        "missing_enforcement": {
            "name": "Test Rule", "pathPattern": "/api/test",
            "methods": ["GET"], "parentImpliesChildren": False,
        },
        "missing_parent_implies_children": {
            "name": "Test Rule", "pathPattern": "/api/test",
            "methods": ["GET"], "enforcement": "REQUIRED",
        },
        "empty_methods": {
            "name": "Test Rule", "pathPattern": "/api/test",
            "methods": [], "enforcement": "REQUIRED", "parentImpliesChildren": False,
        },
        "invalid_method_in_array": {
            "name": "Test Rule", "pathPattern": "/api/test",
            "methods": ["INVALID_METHOD"], "enforcement": "REQUIRED",
            "parentImpliesChildren": False,
        },
        "wrong_types": {
            "name": 12345, "pathPattern": "/api/test",
            "methods": "GET", "enforcement": True, "parentImpliesChildren": "yes",
        },
    }
    return strategies.get(strategy, {})


def make_invalid_bulk_jbac_request(strategy="empty_rules"):
    strategies = {
        "empty_rules": {"rules": []},
        "missing_rules": {},
        "too_many_rules": {"rules": [make_jbac_rule_request() for _ in range(101)]},
    }
    return strategies.get(strategy, {})
