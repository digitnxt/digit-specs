"""
Lifecycle rule tests for IDGen service.
State-transition, version management, immutable-field, and delete rules.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _put(node, url, headers, body):
    r = req_lib.Request("PUT", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _tpl_code():
    return "BR-LC-" + uuid.uuid4().hex[:8].upper()


def _cleanup(base_url, code, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": code, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ---------------------------------------------------------------------------
# BR-LC-001: Template creation enforces code uniqueness
# ---------------------------------------------------------------------------

class TestBR_LC_001_template_creation_enforces_code_uniqueness:
    """Duplicate (tenantID, templateCode) returns 409."""

    def test_duplicate_template_code_returns_409(self, request, base_url, auth_headers):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            resp = _post(request.node, f"{base_url}/template", auth_headers, {
                "templateCode": code,
                "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
            })
            assert resp.status_code == 409, f"Expected 409, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_unique_template_code_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-LC-002: Updates are append-only with version increment
# ---------------------------------------------------------------------------

class TestBR_LC_002_updates_are_append_only_with_version_increment:
    """PUT creates new row with version+1; createdBy/createdTime from v1 preserved."""

    def test_put_creates_v2(self, request, base_url, auth_headers):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            update = _put(request.node, f"{base_url}/template", auth_headers, {
                "templateCode": code,
                "config": {"template": "{SEQ}-V2", "sequence": {"scope": "DAILY", "start": 1}},
            })
            assert update.status_code in (200, 201), f"Expected 200/201, got {update.status_code}: {update.text}"
            assert update.json().get("version") in ("v2", 2), \
                f"Expected version v2 after update, got {update.json().get('version')}"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)

    def test_put_preserves_created_fields_from_v1(self, request, base_url, auth_headers):
        code = _tpl_code()
        create = req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        assert create.status_code == 201, f"Create failed: {create.text}"
        v1 = create.json()
        try:
            update = _put(request.node, f"{base_url}/template", auth_headers, {
                "templateCode": code,
                "config": {"template": "{SEQ}-V2", "sequence": {"scope": "DAILY", "start": 1}},
            })
            assert update.status_code in (200, 201), f"Update failed: {update.text}"
            v2 = update.json()
            if v1.get("auditDetails") and v2.get("auditDetails"):
                assert v2["auditDetails"].get("createdBy") == v1["auditDetails"].get("createdBy"), \
                    "createdBy must be preserved from v1"
                assert v2["auditDetails"].get("createdTime") == v1["auditDetails"].get("createdTime"), \
                    "createdTime must be preserved from v1"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)

    def test_put_on_nonexistent_returns_404(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _put(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        assert resp.status_code == 404, f"Expected 404 for nonexistent template, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-003: Delete targets specific version only
# ---------------------------------------------------------------------------

class TestBR_LC_003_delete_targets_specific_version_only:
    """DELETE with (templateCode, version) removes only that row; other versions unaffected."""

    def test_delete_v1_when_v2_exists_leaves_v2(self, request, base_url, auth_headers):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        req_lib.put(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}-V2", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            del_resp = req_lib.delete(
                f"{base_url}/template",
                params={"templateCode": code, "version": "v1"},
                headers=auth_headers,
            )
            assert del_resp.status_code == 200, f"Delete v1 failed: {del_resp.text}"

            search = req_lib.get(
                f"{base_url}/template",
                params={"templateCode": code, "version": "v2"},
                headers=auth_headers,
            )
            assert search.status_code == 200
            results = search.json()
            assert isinstance(results, list) and len(results) > 0, \
                "v2 must still exist after v1 deletion"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)

    def test_delete_nonexistent_version_returns_404(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": code, "version": "v99"},
            headers=auth_headers,
        )
        assert resp.status_code == 404, f"Expected 404 for nonexistent version, got {resp.status_code}: {resp.text}"
