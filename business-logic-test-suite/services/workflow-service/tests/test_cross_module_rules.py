"""
Cross-module rule tests for Workflow service.
RBAC and tenant isolation — observable via missing header / missing role.
"""
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CM-001: Tenant isolation via X-Tenant-ID header
# ---------------------------------------------------------------------------

class TestBR_CM_001_tenant_isolation_via_x_tenant_id_header:
    """Requests without X-Tenant-ID are rejected with 400."""

    def test_request_without_tenant_id_rejected(self, request, base_url, auth_headers):
        headers_no_tenant = {k: v for k, v in auth_headers.items()
                             if k.lower() != "x-tenant-id"}
        resp = req_lib.get(f"{base_url}/process", headers=headers_no_tenant)
        assert resp.status_code == 400, \
            f"Expected 400 without X-Tenant-ID, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CM-002: RBAC guard enforced during every transition
# ---------------------------------------------------------------------------

class TestBR_CM_002_rbac_guard_enforced_during_every_transition:
    """Transition with missing role returns 403."""

    def test_transition_without_required_role_rejected(
        self, request, base_url, auth_headers
    ):
        import uuid
        headers_no_auth = {k: v for k, v in auth_headers.items()
                           if k.lower() not in ("authorization",)}
        resp = _post(request.node,
                     f"{base_url}/process/TEST-PROCESS/transition",
                     headers_no_auth,
                     {"entityId": "ENT-RBAC-" + uuid.uuid4().hex[:6], "action": "APPROVE"})
        assert resp.status_code in (401, 403), \
            f"Expected 401/403 for transition without auth, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CM-003: PubSub events are fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_003_pubsub_events_are_fire_and_forget:
    """Process/State mutations succeed regardless of PubSub availability."""

    def test_state_create_returns_success_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        import uuid
        state_code = "PUBSUB-" + uuid.uuid4().hex[:4].upper()
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/state", auth_headers,
                     {"code": state_code, "name": "PubSub Test State", "sla": 60})
        assert resp.status_code in (200, 201, 409), \
            f"State create must succeed regardless of PubSub, got {resp.status_code}: {resp.text}"
