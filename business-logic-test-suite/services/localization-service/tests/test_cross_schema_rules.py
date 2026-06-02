"""
Cross-schema rule tests for Localization service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _unique_code():
    return "br-cs-" + uuid.uuid4().hex[:8]


def _msg(code, locale="en_IN", module="test"):
    return {"code": code, "message": "Test message", "module": module, "locale": locale}


# ---------------------------------------------------------------------------
# BR-CS-001: Audit timestamps set once on create
# ---------------------------------------------------------------------------

class TestBR_CS_001_audit_timestamps_set_once_on_create:
    """createdTime is set at create; createdBy is never overwritten after creation."""

    def test_created_message_has_audit_fields(self, request, base_url, auth_headers):
        code = _unique_code()
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [_msg(code)],
        })
        assert resp.status_code in (200, 201), f"Create failed: {resp.text}"
        messages = resp.json().get("messages", resp.json().get("message", []))
        if isinstance(messages, list) and messages:
            msg = messages[0]
            assert msg.get("createdTime") or msg.get("auditDetails", {}).get("createdTime"), \
                "createdTime must be set on creation"


# ---------------------------------------------------------------------------
# BR-CS-002: Tenant isolation at repository level
# ---------------------------------------------------------------------------

class TestBR_CS_002_tenant_isolation_at_repository_level:
    """Every query is scoped to the tenant in X-Tenant-ID; requests without it are rejected."""

    def test_request_without_tenant_header_rejected(self, request, base_url, auth_headers):
        headers_no_tenant = {k: v for k, v in auth_headers.items()
                             if k.lower() != "x-tenant-id"}
        resp = req_lib.get(f"{base_url}/messages", headers=headers_no_tenant,
                           params={"locale": "en_IN", "module": "test"})
        assert resp.status_code == 400, \
            f"Expected 400 without X-Tenant-ID, got {resp.status_code}: {resp.text}"
