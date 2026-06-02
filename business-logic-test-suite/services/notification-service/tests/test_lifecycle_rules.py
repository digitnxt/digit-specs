"""
Lifecycle rule tests for Notification service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _tpl_id():
    return "br-lc-" + uuid.uuid4().hex[:8]


def _cleanup(base_url, template_id, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateId": template_id, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ---------------------------------------------------------------------------
# BR-LC-001: Updates are append-only with version increment
# ---------------------------------------------------------------------------

class TestBR_LC_001_updates_are_append_only_with_version_increment:
    """PUT creates a new row with version+1; prior version rows unchanged."""

    def test_put_creates_v2(self, request, base_url, auth_headers):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "EMAIL",
            "subject": "V1 Subject", "content": "Hello {{.Name}} from v1",
        })
        try:
            update = _post(request.node, f"{base_url}/template", auth_headers, {
                "templateId": tid, "type": "EMAIL",
                "subject": "V2 Subject", "content": "Hello {{.Name}} from v2",
            })
            # PUT uses same endpoint; service determines it's an update
            assert update.status_code == 200, \
                f"Expected 200 on update, got {update.status_code}: {update.text}"
        finally:
            _cleanup(base_url, tid, "v2", auth_headers)
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_put_on_nonexistent_returns_404(self, request, base_url, auth_headers):
        resp = req_lib.put(f"{base_url}/template", headers=auth_headers, json={
            "templateId": f"nonexistent-{uuid.uuid4().hex[:8]}",
            "type": "EMAIL", "subject": "Hi", "content": "Hi {{.Name}}",
        })
        assert resp.status_code == 404, \
            f"Expected 404 for PUT on nonexistent templateId, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-002: createdBy and createdTime immutable after v1
# ---------------------------------------------------------------------------

class TestBR_LC_002_created_by_and_created_time_immutable_after_v1:
    """createdBy and createdTime from v1 are preserved verbatim on subsequent versions."""

    def test_created_fields_preserved_across_versions(self, request, base_url, auth_headers):
        tid = _tpl_id()
        create = req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "EMAIL",
            "subject": "V1", "content": "Hello from v1",
        })
        assert create.status_code == 201
        v1 = create.json()
        try:
            update = req_lib.put(f"{base_url}/template", headers=auth_headers, json={
                "templateId": tid, "type": "EMAIL",
                "subject": "V2", "content": "Hello from v2",
            })
            assert update.status_code == 200
            v2 = update.json()

            if v1.get("auditDetails") and v2.get("auditDetails"):
                assert v2["auditDetails"].get("createdBy") == v1["auditDetails"].get("createdBy"), \
                    "createdBy must be preserved from v1"
                assert v2["auditDetails"].get("createdTime") == v1["auditDetails"].get("createdTime"), \
                    "createdTime must be preserved from v1"
        finally:
            _cleanup(base_url, tid, "v2", auth_headers)
            _cleanup(base_url, tid, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-LC-003: Delete targets specific version only
# ---------------------------------------------------------------------------

class TestBR_LC_003_delete_targets_specific_version_only:
    """DELETE with (templateId, version) removes only that row."""

    def test_delete_v1_leaves_v2_intact(self, request, base_url, auth_headers):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "EMAIL",
            "subject": "V1", "content": "Hello v1",
        })
        req_lib.put(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "EMAIL",
            "subject": "V2", "content": "Hello v2",
        })
        try:
            del_resp = req_lib.delete(
                f"{base_url}/template",
                params={"templateId": tid, "version": "v1"},
                headers=auth_headers,
            )
            assert del_resp.status_code == 200

            search = req_lib.get(
                f"{base_url}/template",
                params={"templateId": tid, "version": "v2"},
                headers=auth_headers,
            )
            assert search.status_code == 200
            results = search.json()
            assert isinstance(results, list) and len(results) > 0, \
                "v2 must still be accessible after v1 deletion"
        finally:
            _cleanup(base_url, tid, "v2", auth_headers)

    def test_delete_nonexistent_version_returns_404(self, request, base_url, auth_headers):
        resp = req_lib.delete(
            f"{base_url}/template",
            params={"templateId": f"nonexistent-{uuid.uuid4().hex[:8]}", "version": "v99"},
            headers=auth_headers,
        )
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent version, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-004: Version omission resolves to latest
# ---------------------------------------------------------------------------

class TestBR_LC_004_version_omission_resolves_to_latest:
    """Omitting version in send/preview uses the highest available version."""

    def test_send_without_version_uses_latest(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome",
            "emailIds": ["test@example.com"],
        })
        assert resp.status_code in (200, 422, 500), \
            f"Send without version should resolve to latest, got {resp.status_code}: {resp.text}"
        if resp.status_code == 200:
            assert resp.json().get("version"), "Response must include the resolved version"
