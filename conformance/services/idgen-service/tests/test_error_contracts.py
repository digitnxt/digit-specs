import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_template,
    make_invalid_template,
    make_generate_request,
    make_invalid_generate,
    make_bulk_generate_request,
    make_invalid_bulk_generate,
    _tpl_code,
)
from tests.helpers.validators import assert_gateway_headers


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _delete_template(base_url, code, version, headers):
    try:
        req_lib.delete(f"{base_url}/template",
                       params={"templateCode": code, "version": version},
                       headers=headers)
    except Exception:
        pass


# ── Template Create — negative ────────────────────────────────────────────────

class TestTemplateCreateNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_missing_template_code_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("missing_template_code"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_missing_config_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("missing_config"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_template_code_too_short_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("template_code_too_short"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_invalid_sequence_scope_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("invalid_sequence_scope"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_invalid_charset_cross_class_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("invalid_charset_cross_class"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_padding_start_overflow_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("padding_start_overflow"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_duplicate_template_code_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template(code=code))
            assert r.status_code == 409, f"Expected 409 for duplicate, got {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  json_body=make_template())
        assert r.status_code == 401, f"Expected 401 for missing auth, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers={"Authorization": "Bearer invalid-token-xyz"},
                  json_body=make_template())
        assert r.status_code == 401, f"Expected 401 for invalid token, got {r.status_code}: {r.text}"


# ── Template Update — negative ────────────────────────────────────────────────

class TestTemplateUpdateNegativeContracts:
    def test_update_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "PUT", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_template(code=f"NONEXISTENT-{_tpl_code()}"))
        assert r.status_code == 404, f"Expected 404 for nonexistent template, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_update_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "PUT", f"{base_url}/template",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_update_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "PUT", f"{base_url}/template",
                  json_body=make_template())
        assert r.status_code == 401, f"Expected 401 for missing auth, got {r.status_code}: {r.text}"


# ── Template Delete — negative ────────────────────────────────────────────────

class TestTemplateDeleteNegativeContracts:
    def test_delete_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers,
                  params={"templateCode": f"NONEXISTENT-{_tpl_code()}", "version": "v1"})
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_delete_missing_template_code_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers, params={"version": "v1"})
        assert r.status_code == 400, f"Expected 400 for missing templateCode, got {r.status_code}: {r.text}"

    def test_delete_missing_version_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers, params={"templateCode": "some-template"})
        assert r.status_code == 400, f"Expected 400 for missing version, got {r.status_code}: {r.text}"

    def test_delete_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  params={"templateCode": "some-template", "version": "v1"})
        assert r.status_code == 401, f"Expected 401 for missing auth, got {r.status_code}: {r.text}"


# ── Generate ID — negative ────────────────────────────────────────────────────

class TestGenerateIDNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_template_code_too_short_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate",
                  headers=auth_headers,
                  json_body=make_invalid_generate("template_code_too_short"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_nonexistent_template_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate",
                  headers=auth_headers,
                  json_body=make_generate_request(f"NONEXISTENT-{_tpl_code()}"))
        assert r.status_code == 404, f"Expected 404 for nonexistent template, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_missing_variable_returns_422(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json={"templateCode": code, "config": {"template": "{ORG}-{SEQ}"}},
                     headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers,
                      json_body=make_generate_request(code))
            assert r.status_code == 422, (
                f"Expected 422 for missing {'{ORG}'} variable, got {r.status_code}: {r.text}"
            )
            assert_gateway_headers(r, gateway_headers_spec)
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate",
                  json_body=make_generate_request("some-template"))
        assert r.status_code == 401, f"Expected 401 for missing auth, got {r.status_code}: {r.text}"

    def test_invalid_token_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate",
                  headers={"Authorization": "Bearer invalid-token-xyz"},
                  json_body=make_generate_request("some-template"))
        assert r.status_code == 401, f"Expected 401 for invalid token, got {r.status_code}: {r.text}"


# ── Bulk Generate — negative ──────────────────────────────────────────────────

class TestBulkGenerateNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_missing_count_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                  headers=auth_headers,
                  json_body=make_invalid_bulk_generate("missing_count"))
        assert r.status_code == 400, f"Expected 400 for missing count, got {r.status_code}: {r.text}"

    def test_zero_count_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                  headers=auth_headers,
                  json_body=make_invalid_bulk_generate("zero_count"))
        assert r.status_code == 400, f"Expected 400 for count=0, got {r.status_code}: {r.text}"

    def test_excess_count_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                  headers=auth_headers,
                  json_body=make_invalid_bulk_generate("excess_count"))
        assert r.status_code == 400, f"Expected 400 for count=1001, got {r.status_code}: {r.text}"

    def test_nonexistent_template_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                  headers=auth_headers,
                  json_body=make_bulk_generate_request(f"NONEXISTENT-{_tpl_code()}", count=5))
        assert r.status_code == 404, f"Expected 404 for nonexistent template, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                  json_body=make_bulk_generate_request("some-template", count=5))
        assert r.status_code == 401, f"Expected 401 for missing auth, got {r.status_code}: {r.text}"

    def test_missing_variable_returns_422(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json={"templateCode": code, "config": {"template": "{DEPT}-{SEQ}"}},
                     headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=3))
            assert r.status_code == 422, (
                f"Expected 422 for missing {'{DEPT}'} variable, got {r.status_code}: {r.text}"
            )
            assert_gateway_headers(r, gateway_headers_spec)
        finally:
            _delete_template(base_url, code, "v1", auth_headers)
