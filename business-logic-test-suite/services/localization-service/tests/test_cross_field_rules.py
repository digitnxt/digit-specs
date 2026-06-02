"""
Cross-field rule tests for Localization service.
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
    return "br-cf-" + uuid.uuid4().hex[:8]


def _msg(code, locale="en_IN", module="test"):
    return {"code": code, "message": "Test message", "module": module, "locale": locale}


# ---------------------------------------------------------------------------
# BR-CF-001: Composite key uniqueness enforced
# ---------------------------------------------------------------------------

class TestBR_CF_001_composite_key_uniqueness_enforced:
    """(tenant_id, locale, module, code) must be unique; Create→409 on conflict, Upsert→silent update."""

    def test_create_with_unique_key_accepted(self, request, base_url, auth_headers):
        code = _unique_code()
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [_msg(code)],
        })
        assert resp.status_code in (200, 201), f"Expected 200/201, got {resp.status_code}: {resp.text}"

    def test_create_with_duplicate_key_returns_409(self, request, base_url, auth_headers):
        code = _unique_code()
        req_lib.post(f"{base_url}/messages", headers=auth_headers, json={"messages": [_msg(code)]})
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [_msg(code)],
        })
        assert resp.status_code == 409, f"Expected 409 for duplicate key, got {resp.status_code}: {resp.text}"

    def test_upsert_with_existing_key_updates_silently(self, request, base_url, auth_headers):
        code = _unique_code()
        req_lib.post(f"{base_url}/messages", headers=auth_headers, json={"messages": [_msg(code)]})
        updated_msg = {**_msg(code), "message": "Updated message text"}
        resp = req_lib.put(f"{base_url}/messages/upsert", headers=auth_headers,
                           json={"messages": [updated_msg]})
        assert resp.status_code in (200, 201), f"Upsert with existing key must succeed, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Required fields on create and upsert
# ---------------------------------------------------------------------------

class TestBR_CF_003_required_fields_on_create_and_upsert:
    """code, message, module, locale are all required."""

    def test_missing_code_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [{"message": "Hello", "module": "test", "locale": "en_IN"}],
        })
        assert resp.status_code == 400, f"Expected 400 for missing code, got {resp.status_code}: {resp.text}"

    def test_missing_message_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [{"code": _unique_code(), "module": "test", "locale": "en_IN"}],
        })
        assert resp.status_code == 400, f"Expected 400 for missing message, got {resp.status_code}: {resp.text}"

    def test_missing_locale_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [{"code": _unique_code(), "message": "Hi", "module": "test"}],
        })
        assert resp.status_code == 400, f"Expected 400 for missing locale, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: UUID format validity on update
# ---------------------------------------------------------------------------

class TestBR_CF_004_uuid_format_validity_on_update:
    """Malformed UUIDs in update requests are rejected."""

    def test_malformed_uuid_on_update_rejected(self, request, base_url, auth_headers):
        resp = req_lib.patch(f"{base_url}/messages", headers=auth_headers, json={
            "messages": [{
                "uuid": "not-a-valid-uuid",
                "message": "Updated text",
            }],
        })
        assert resp.status_code == 400, f"Expected 400 for malformed UUID, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Delete requires at least one UUID
# ---------------------------------------------------------------------------

class TestBR_CF_005_delete_requires_at_least_one_uuid:
    """DELETE with no UUID parameters is rejected."""

    def test_delete_without_uuid_rejected(self, request, base_url, auth_headers):
        resp = req_lib.delete(f"{base_url}/messages", headers=auth_headers)
        assert resp.status_code == 400, f"Expected 400 for delete without UUID, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: X-Tenant-ID header mandatory on every request
# ---------------------------------------------------------------------------

class TestBR_CF_002_x_tenant_id_header_mandatory:
    """Absence of X-Tenant-ID returns 400 before any business logic executes."""

    def test_missing_tenant_id_returns_400(self, request, base_url, auth_headers):
        headers_no_tenant = {k: v for k, v in auth_headers.items()
                             if k.lower() != "x-tenant-id"}
        r = req_lib.get(f"{base_url}/messages", headers=headers_no_tenant,
                        params={"locale": "en_IN", "module": "test"})
        assert r.status_code == 400, \
            f"Expected 400 for missing X-Tenant-ID, got {r.status_code}: {r.text}"

    def test_request_with_tenant_id_proceeds(self, request, base_url, auth_headers):
        r = req_lib.get(f"{base_url}/messages", headers=auth_headers,
                        params={"locale": "en_IN", "module": "test"})
        assert r.status_code in (200, 404), \
            f"Request with X-Tenant-ID must proceed past header check, got {r.status_code}: {r.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: Paginated search bypasses cache
# ---------------------------------------------------------------------------

class TestBR_CF_006_paginated_search_bypasses_cache:
    """
    Requests with limit > 0 bypass the cache entirely.
    Observable via consistent results immediately after a write (no stale cache).
    """

    def test_paginated_search_returns_freshly_written_data(self, request, base_url, auth_headers):
        code = _unique_code()
        req_lib.post(f"{base_url}/messages", headers=auth_headers,
                     json={"messages": [_msg(code, message="Fresh message")]})
        search = req_lib.get(f"{base_url}/messages", headers=auth_headers,
                             params={"locale": "en_IN", "module": "test", "limit": 10})
        assert search.status_code == 200, f"Paginated search must succeed: {search.text}"
        messages = search.json()
        result_codes = [m.get("code") for m in (messages if isinstance(messages, list) else [])]
        assert code in result_codes, \
            f"Paginated search must return freshly written message '{code}' (bypasses cache)"


    def test_delete_with_uuid_accepted(self, request, base_url, auth_headers):
        code = _unique_code()
        create = req_lib.post(f"{base_url}/messages", headers=auth_headers,
                              json={"messages": [_msg(code)]})
        if create.status_code not in (200, 201):
            return
        msgs = create.json().get("messages", create.json().get("message", []))
        if not msgs:
            return
        msg_uuid = msgs[0].get("uuid") if isinstance(msgs, list) else None
        if not msg_uuid:
            return
        resp = req_lib.delete(f"{base_url}/messages", headers=auth_headers,
                              params={"uuid": msg_uuid})
        assert resp.status_code in (200, 204), f"Delete with UUID must succeed, got {resp.status_code}: {resp.text}"
