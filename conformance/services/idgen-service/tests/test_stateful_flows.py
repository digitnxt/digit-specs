import re
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_template,
    make_template_dated,
    make_template_update,
    make_template_with_variable,
    make_generate_request,
    make_bulk_generate_request,
    _tpl_code,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_template_response_shape,
    assert_generate_response_shape,
    assert_bulk_generate_response_shape,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(base_url, code, version, headers):
    try:
        req_lib.delete(f"{base_url}/template",
                       params={"templateCode": code, "version": version},
                       headers=headers)
    except Exception:
        pass


# ── Template Lifecycle ────────────────────────────────────────────────────────

class TestTemplateLifecycle:
    def test_create_search_update_delete(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        v2_created = False
        try:
            # 1. CREATE v1
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_template_response_shape(body)
            assert body["templateCode"] == code
            assert body["version"] == "v1"

            # 2. SEARCH — latest for this code
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code})
            assert r.status_code == 200
            results = r.json()
            assert isinstance(results, list)
            assert any(t["templateCode"] == code for t in results), (
                f"Created template '{code}' not found in search"
            )

            # 3. SEARCH by code + version
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v1"})
            assert r.status_code == 200
            v1_results = r.json()
            assert len(v1_results) >= 1
            assert v1_results[0]["version"] == "v1"

            # 4. UPDATE → v2
            r = _send(request.node, "PUT", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template_update(code=code))
            assert r.status_code == 200, f"Update failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert body["version"] == "v2"
            assert_template_response_shape(body)
            v2_created = True

            # 5. SEARCH latest — must return v2
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code})
            assert r.status_code == 200
            results = r.json()
            latest = next((t for t in results if t["templateCode"] == code), None)
            assert latest is not None, f"Template '{code}' not found after update"
            assert latest["version"] == "v2", (
                f"Expected latest version v2, got {latest['version']}"
            )

            # 6. v1 still accessible by explicit version
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v1"})
            assert r.status_code == 200
            assert len(r.json()) >= 1

            # 7. DELETE v2
            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v2"})
            assert r.status_code == 200, f"Delete v2 failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)
            v2_created = False

            # 8. DELETE v1
            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v1"})
            assert r.status_code == 200, f"Delete v1 failed: {r.text}"
            assert r.json().get("deleted") is True
            code = None

        finally:
            if code:
                if v2_created:
                    _cleanup(base_url, code, "v2", auth_headers)
                _cleanup(base_url, code, "v1", auth_headers)


# ── ID Generation Flow ────────────────────────────────────────────────────────

class TestIDGenerationFlow:
    def test_create_template_generate_validate_format(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        try:
            # 1. CREATE dated template — pattern: {DATE:yyyymmdd}-{SEQ}
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template_dated(code=code))
            assert r.status_code == 201, f"Create failed: {r.text}"

            # 2. GENERATE
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200, f"Generate failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_generate_response_shape(body)
            assert body["templateCode"] == code
            assert body["version"] == "v1"

            # 3. FORMAT validation — {DATE:yyyymmdd}-{SEQ} → YYYYMMDD-NNNN
            generated_id = body["id"]
            assert re.match(r'^\d{8}-\d{4}$', generated_id), (
                f"ID '{generated_id}' does not match expected format YYYYMMDD-NNNN"
            )

            # 4. SECOND generate — sequence must increment, ID must differ
            r2 = _send(request.node, "POST", f"{base_url}/generate",
                       headers=auth_headers, json_body=make_generate_request(code))
            assert r2.status_code == 200
            assert r2.json()["id"] != generated_id, "Consecutive IDs must differ"

        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_generate_with_variable_template(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template_with_variable(code=code))
            assert r.status_code == 201, f"Create failed: {r.text}"

            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers,
                      json_body=make_generate_request(code, variables={"ORG": "MYORG"}))
            assert r.status_code == 200, f"Generate failed: {r.text}"
            body = r.json()
            assert_generate_response_shape(body)
            assert "MYORG" in body["id"], f"Variable 'MYORG' missing from ID: {body['id']}"

        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_sequence_is_monotonically_increasing(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 201

            ids = []
            for _ in range(5):
                r = _send(request.node, "POST", f"{base_url}/generate",
                          headers=auth_headers, json_body=make_generate_request(code))
                assert r.status_code == 200
                ids.append(r.json()["id"])

            # All IDs must be distinct (SEQ advances each call)
            assert len(set(ids)) == 5, f"Non-unique IDs in sequence: {ids}"

            # IDs are zero-padded SEQ — lexicographic sort matches generation order
            assert ids == sorted(ids), (
                f"IDs are not in monotonically increasing order: {ids}"
            )

        finally:
            _cleanup(base_url, code, "v1", auth_headers)


# ── Bulk Generation Flow ──────────────────────────────────────────────────────

class TestBulkGenerationFlow:
    def test_bulk_generate_unique_sequential_ids(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 201

            count = 5
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=count))
            assert r.status_code == 200, f"Bulk generate failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_bulk_generate_response_shape(body, count)

            # All IDs must be unique
            ids = body["ids"]
            assert len(set(ids)) == count, f"Duplicate IDs in bulk response: {ids}"

            # IDs (zero-padded SEQ) are in sequence order
            assert ids == sorted(ids), (
                f"Bulk IDs not in sequence order: {ids}"
            )

        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_single_generate_continues_after_bulk(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 201

            # Bulk consume 5 sequence values
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=5))
            assert r.status_code == 200
            bulk_ids = r.json()["ids"]

            # Single generate must not reuse any bulk ID
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200
            single_id = r.json()["id"]
            assert single_id not in bulk_ids, (
                f"Post-bulk single ID '{single_id}' duplicates a bulk ID"
            )

        finally:
            _cleanup(base_url, code, "v1", auth_headers)


# ── Template Versioning ───────────────────────────────────────────────────────

class TestTemplateVersioning:
    def test_generate_uses_latest_version_after_update(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        v2_created = False
        try:
            # v1: plain SEQ template
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 201

            # Generate from v1
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200
            v1_gen = r.json()
            assert v1_gen["version"] == "v1"

            # UPDATE → v2 (dated + rand template)
            r = _send(request.node, "PUT", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template_update(code=code))
            assert r.status_code == 200
            v2_created = True

            # Generate now uses v2
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200
            v2_gen = r.json()
            assert v2_gen["version"] == "v2", (
                f"Expected v2 after update, got: {v2_gen.get('version')}"
            )
            assert_gateway_headers(r, gateway_headers_spec)

            # v1 and v2 produce different ID formats — they must differ
            assert v2_gen["id"] != v1_gen["id"]

            # v1 template row still visible in search
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v1"})
            assert r.status_code == 200
            v1_rows = r.json()
            assert len(v1_rows) >= 1 and v1_rows[0]["version"] == "v1"

        finally:
            if v2_created:
                _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)

    def test_delete_latest_version_fallback_to_previous(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        try:
            # Create v1
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 201

            # Update → v2
            r = _send(request.node, "PUT", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template_update(code=code))
            assert r.status_code == 200

            # Delete v2 — v1 should still exist
            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v2"})
            assert r.status_code == 200
            assert r.json().get("deleted") is True

            # v1 still searchable
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code})
            assert r.status_code == 200
            results = r.json()
            assert any(t["templateCode"] == code for t in results), (
                f"v1 template '{code}' not found after v2 deletion"
            )

            # Generate still works against v1
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200
            assert r.json()["version"] == "v1"

        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)
