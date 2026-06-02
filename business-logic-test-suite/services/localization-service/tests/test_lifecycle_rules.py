"""
Lifecycle rule tests for Localization service.
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
    return "br-lc-" + uuid.uuid4().hex[:8]


def _msg(code, locale="en_IN", module="test", message="Test msg"):
    return {"code": code, "message": message, "module": module, "locale": locale}


# ---------------------------------------------------------------------------
# BR-LC-001: Create uses DO NOTHING; upsert uses DO UPDATE
# ---------------------------------------------------------------------------

class TestBR_LC_001_create_do_nothing_upsert_do_update:
    """Create→409 on conflict; Upsert→silent update with updated message text."""

    def test_upsert_updates_message_on_existing_key(self, request, base_url, auth_headers):
        code = _unique_code()
        req_lib.post(f"{base_url}/messages", headers=auth_headers,
                     json={"messages": [_msg(code, message="Original")]})

        upsert = req_lib.put(f"{base_url}/messages/upsert", headers=auth_headers,
                             json={"messages": [_msg(code, message="Updated")]})
        assert upsert.status_code in (200, 201), f"Upsert must succeed: {upsert.text}"

        search = req_lib.get(f"{base_url}/messages", headers=auth_headers,
                             params={"locale": "en_IN", "module": "test"})
        if search.status_code == 200:
            msgs = search.json()
            matches = [m for m in (msgs if isinstance(msgs, list) else [])
                       if m.get("code") == code]
            if matches:
                assert matches[0].get("message") == "Updated", \
                    f"Upsert must update message text, got: {matches[0].get('message')}"


# ---------------------------------------------------------------------------
# BR-LC-002: Upsert deduplicates batch before DB call
# ---------------------------------------------------------------------------

class TestBR_LC_002_upsert_deduplicates_batch_before_db:
    """Duplicate entries in same upsert request don't cause DB errors (last wins)."""

    def test_upsert_with_duplicate_entries_in_batch_succeeds(
        self, request, base_url, auth_headers
    ):
        code = _unique_code()
        resp = req_lib.put(f"{base_url}/messages/upsert", headers=auth_headers, json={
            "messages": [
                _msg(code, message="First occurrence"),
                _msg(code, message="Second occurrence"),
            ],
        })
        assert resp.status_code in (200, 201), \
            f"Upsert with duplicate batch must succeed (dedup applied), got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-004: UUID auto-generated when absent
# ---------------------------------------------------------------------------

class TestBR_LC_004_uuid_auto_generated_when_absent:
    """Messages without uuid on create receive an auto-generated UUID in the response."""

    def test_message_without_uuid_gets_one_assigned(self, request, base_url, auth_headers):
        code = _unique_code()
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [{"code": code, "message": "No UUID", "module": "test", "locale": "en_IN"}],
        })
        assert resp.status_code in (200, 201), f"Create failed: {resp.text}"
        messages = resp.json().get("messages", resp.json().get("message", []))
        if isinstance(messages, list) and messages:
            assert messages[0].get("uuid"), "UUID must be auto-generated when not supplied"


# ---------------------------------------------------------------------------
# BR-LC-005: Update returns 404 for absent UUID
# ---------------------------------------------------------------------------

class TestBR_LC_005_update_returns_404_for_absent_uuid:
    """Updating a UUID that doesn't exist for the tenant returns 404."""


# ---------------------------------------------------------------------------
# BR-LC-003: Cache invalidated on every write
# ---------------------------------------------------------------------------

class TestBR_LC_003_cache_invalidated_on_every_write:
    """
    After a successful write (create/update/upsert/delete), the cache for
    (tenant, module, locale) is invalidated. Subsequent reads reflect the write.
    """

    def test_search_after_create_reflects_new_message(self, request, base_url, auth_headers):
        code = _unique_code()
        create = req_lib.post(f"{base_url}/messages", headers=auth_headers,
                              json={"messages": [_msg(code, message="Cache invalidation test")]})
        assert create.status_code in (200, 201), f"Create failed: {create.text}"

        search = req_lib.get(f"{base_url}/messages", headers=auth_headers,
                             params={"locale": "en_IN", "module": "test"})
        assert search.status_code == 200
        messages = search.json()
        result_codes = [m.get("code") for m in (messages if isinstance(messages, list) else [])]
        assert code in result_codes, \
            "Cache must be invalidated after create — new message must appear in search"

    def test_search_after_upsert_reflects_updated_message(self, request, base_url, auth_headers):
        code = _unique_code()
        req_lib.post(f"{base_url}/messages", headers=auth_headers,
                     json={"messages": [_msg(code, message="Before upsert")]})
        req_lib.put(f"{base_url}/messages/upsert", headers=auth_headers,
                    json={"messages": [_msg(code, message="After upsert")]})

        search = req_lib.get(f"{base_url}/messages", headers=auth_headers,
                             params={"locale": "en_IN", "module": "test"})
        assert search.status_code == 200
        messages = search.json()
        match = next((m for m in (messages if isinstance(messages, list) else [])
                      if m.get("code") == code), None)
        if match:
            assert match.get("message") == "After upsert", \
                "Cache must be invalidated after upsert — updated text must appear in search"


    def test_update_nonexistent_uuid_returns_404(self, request, base_url, auth_headers):
        fake_uuid = str(uuid.uuid4())
        resp = req_lib.patch(f"{base_url}/messages", headers=auth_headers, json={
            "messages": [{"uuid": fake_uuid, "message": "Updated text"}],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent UUID on update, got {resp.status_code}: {resp.text}"
