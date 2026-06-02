"""
Cross-module rule tests for Boundary service.
CM-001: tenant isolation (architectural, observable via missing header).
CM-002: PubSub fire-and-forget (observable via write success).
"""
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CM-001: Tenant isolation across all operations
# ---------------------------------------------------------------------------

class TestBR_CM_001_tenant_isolation_across_all_operations:
    """Request without X-Tenant-ID is rejected with 400."""

    def test_request_without_tenant_id_header_rejected(
        self, request, base_url, auth_headers
    ):
        headers_no_tenant = {k: v for k, v in auth_headers.items()
                             if k.lower() != "x-tenant-id"}
        resp = req_lib.get(f"{base_url}/boundaries", headers=headers_no_tenant)
        assert resp.status_code == 400, \
            f"Expected 400 without X-Tenant-ID, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CM-002: PubSub publish is fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_002_pubsub_publish_is_fire_and_forget:
    """Write operations succeed regardless of PubSub availability."""

    def test_boundary_create_returns_success_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        import uuid
        code = "BR-CM-" + uuid.uuid4().hex[:6].upper()
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{"code": code}],
        })
        assert resp.status_code in (200, 201), \
            f"Create must succeed regardless of PubSub availability, got {resp.status_code}: {resp.text}"
