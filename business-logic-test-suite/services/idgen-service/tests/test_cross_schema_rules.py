"""
Cross-schema rule tests for IDGen service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _delete(node, url, headers, params=None):
    r = req_lib.Request("DELETE", url, headers=headers, params=params)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _tpl_code():
    return "BR-CS-" + uuid.uuid4().hex[:8].upper()


def _cleanup(base_url, code, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": code, "version": version},
            headers=headers,
        )
    except Exception:
        pass


def _make_template(code):
    return {
        "templateCode": code,
        "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
    }


# ---------------------------------------------------------------------------
# BR-CS-001: TemplateCode must exist before ID generation
# ---------------------------------------------------------------------------

class TestBR_CS_001_templatecode_must_exist_before_id_generation:
    """POST /generate and POST /generate/bulk reject unknown templateCodes with 404."""

    def test_generate_with_existing_template_succeeds(
        self, request, base_url, auth_headers
    ):
        # seed_manifest provisions TEST-TEMPLATE; generation should succeed
        resp = _post(request.node, f"{base_url}/generate", auth_headers,
                     {"templateCode": "TEST-TEMPLATE"})
        assert resp.status_code == 200, \
            f"Expected 200 with existing template, got {resp.status_code}: {resp.text}"

    def test_generate_with_nonexistent_templatecode_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/generate", auth_headers,
                     {"templateCode": f"NONEXISTENT-{_tpl_code()}"})
        assert resp.status_code == 404, \
            f"Expected 404 for unknown templateCode, got {resp.status_code}: {resp.text}"

    def test_bulk_generate_with_nonexistent_templatecode_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/generate/bulk", auth_headers,
                     {"templateCode": f"NONEXISTENT-{_tpl_code()}", "count": 3})
        assert resp.status_code == 404, \
            f"Expected 404 on bulk generate for unknown templateCode, got {resp.status_code}: {resp.text}"

    def test_generate_after_template_deleted_returns_404(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers,
                     json=_make_template(code))
        req_lib.delete(f"{base_url}/template",
                       params={"templateCode": code, "version": "v1"},
                       headers=auth_headers)
        resp = _post(request.node, f"{base_url}/generate", auth_headers,
                     {"templateCode": code})
        assert resp.status_code == 404, \
            f"Expected 404 after template deletion, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-002: One PostgreSQL sequence per template per tenant
# ---------------------------------------------------------------------------

class TestBR_CS_002_one_sequence_per_template_per_tenant:
    """A single PostgreSQL sequence is created at template creation time;
    duplicate templateCode returns 409."""

    def test_first_create_succeeds(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers,
                     _make_template(code))
        try:
            assert resp.status_code == 201, \
                f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_second_create_same_code_returns_409(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers,
                     json=_make_template(code))
        try:
            resp = _post(request.node, f"{base_url}/template", auth_headers,
                         _make_template(code))
            assert resp.status_code == 409, \
                f"Expected 409 on duplicate, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CS-003: Sequence only dropped on last version delete
# ---------------------------------------------------------------------------

class TestBR_CS_003_sequence_only_dropped_on_last_version_delete:
    """Deleting a non-last version leaves the sequence intact; generation still works."""

    def test_delete_v1_when_v2_exists_leaves_generation_working(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers,
                     json=_make_template(code))
        req_lib.put(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            del_v1 = _delete(
                request.node, f"{base_url}/template", auth_headers,
                params={"templateCode": code, "version": "v1"},
            )
            assert del_v1.status_code == 200, f"Delete v1 failed: {del_v1.text}"

            gen = req_lib.post(f"{base_url}/generate", headers=auth_headers,
                               json={"templateCode": code})
            assert gen.status_code == 200, \
                f"Generation after v1 deletion must succeed (sequence retained): {gen.text}"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CS-004: Global scope start cannot be updated
# ---------------------------------------------------------------------------

class TestBR_CS_004_global_scope_start_cannot_be_updated:
    """Updating sequence.start on a GLOBAL-scope template is rejected with 400."""

    def test_changing_global_start_on_update_rejected(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "GLOBAL", "start": 1}},
        })
        try:
            update = req_lib.put(f"{base_url}/template", headers=auth_headers, json={
                "templateCode": code,
                "config": {"template": "{SEQ}", "sequence": {"scope": "GLOBAL", "start": 100}},
            })
            assert update.status_code == 400, \
                f"Expected 400 for GLOBAL start change, got {update.status_code}: {update.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_changing_daily_scope_start_on_update_accepted(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            update = req_lib.put(f"{base_url}/template", headers=auth_headers, json={
                "templateCode": code,
                "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 100}},
            })
            assert update.status_code in (200, 201), \
                f"Expected 200/201 for DAILY start change, got {update.status_code}: {update.text}"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)

    def test_same_global_start_on_update_accepted(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "GLOBAL", "start": 1}},
        })
        try:
            update = req_lib.put(f"{base_url}/template", headers=auth_headers, json={
                "templateCode": code,
                "config": {"template": "{SEQ}-V2", "sequence": {"scope": "GLOBAL", "start": 1}},
            })
            assert update.status_code in (200, 201), \
                f"Expected 200/201 for same GLOBAL start, got {update.status_code}: {update.text}"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CS-005: Scope reset rows deleted with last version
# ---------------------------------------------------------------------------

class TestBR_CS_005_scope_reset_rows_deleted_with_last_version:
    """Deleting the last version succeeds; generation for that code returns 404 after."""

    def test_delete_only_version_returns_200_with_deleted_true(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers,
                     json=_make_template(code))
        resp = _delete(
            request.node, f"{base_url}/template", auth_headers,
            params={"templateCode": code, "version": "v1"},
        )
        assert resp.status_code == 200, \
            f"Expected 200 on last version delete, got {resp.status_code}: {resp.text}"
        assert resp.json().get("deleted") is True, \
            "Response must contain deleted: true"

    def test_generation_after_full_deletion_returns_404(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers,
                     json=_make_template(code))
        req_lib.delete(f"{base_url}/template",
                       params={"templateCode": code, "version": "v1"},
                       headers=auth_headers)
        gen = req_lib.post(f"{base_url}/generate", headers=auth_headers,
                           json={"templateCode": code})
        assert gen.status_code == 404, \
            f"Expected 404 after last-version deletion, got {gen.status_code}: {gen.text}"
