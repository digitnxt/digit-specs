import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_email_template,
    make_sms_template,
    make_template_update,
    make_invalid_template,
    make_invalid_preview_request,
    make_invalid_email_request,
    make_invalid_sms_request,
    make_email_request,
    make_sms_request,
    _tpl_id,
)
from tests.helpers.validators import assert_gateway_headers, assert_error_array


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _delete_template(base_url, template_id, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateId": template_id, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ── Template Create — negative ────────────────────────────────────────────────

class TestTemplateCreateNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_missing_template_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("missing_template_id"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_missing_type_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("missing_type"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_missing_content_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("missing_content"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_invalid_type_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("invalid_type"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_template_id_too_long_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("template_id_too_long"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_empty_content_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_invalid_template("content_empty"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_duplicate_template_id_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers,
                      json_body=make_email_template(template_id=tid))
            assert r.status_code == 409, f"Expected 409 for duplicate, got {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_error_array(r.json())
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  json_body=make_email_template())
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers={"Authorization": "Bearer invalid-token-xyz"},
                  json_body=make_email_template())
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"


# ── Template Update — negative ────────────────────────────────────────────────

class TestTemplateUpdateNegativeContracts:
    def test_update_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "PUT", f"{base_url}/template",
                  headers=auth_headers,
                  json_body=make_template_update(f"NONEXISTENT-{_tpl_id()}"))
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_update_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "PUT", f"{base_url}/template",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_update_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "PUT", f"{base_url}/template",
                  json_body=make_template_update(_tpl_id()))
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"


# ── Template Delete — negative ────────────────────────────────────────────────

class TestTemplateDeleteNegativeContracts:
    def test_delete_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers,
                  params={"templateId": f"NONEXISTENT-{_tpl_id()}", "version": "v1"})
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_delete_missing_template_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers, params={"version": "v1"})
        assert r.status_code == 400, f"Expected 400 for missing templateId, got {r.status_code}: {r.text}"

    def test_delete_missing_version_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers, params={"templateId": "some-template"})
        assert r.status_code == 400, f"Expected 400 for missing version, got {r.status_code}: {r.text}"

    def test_delete_invalid_version_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers,
                  params={"templateId": "some-template", "version": "latest"})
        assert r.status_code == 400, f"Expected 400 for invalid version, got {r.status_code}: {r.text}"

    def test_delete_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  params={"templateId": "some-template", "version": "v1"})
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"


# ── Template Preview — negative ───────────────────────────────────────────────

class TestTemplatePreviewNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template/preview",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_invalid_version_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template/preview",
                  headers=auth_headers,
                  json_body=make_invalid_preview_request("invalid_version"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_nonexistent_template_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template/preview",
                  headers=auth_headers,
                  json_body={"templateId": f"NONEXISTENT-{_tpl_id()}"})
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template/preview",
                  json_body={"templateId": _tpl_id()})
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"


# ── Email Send — negative ─────────────────────────────────────────────────────

class TestEmailSendNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_missing_template_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_invalid_email_request("missing_template_id"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_missing_email_ids_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_invalid_email_request("missing_email_ids"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_empty_email_ids_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_invalid_email_request("empty_email_ids"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_invalid_email_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_invalid_email_request("invalid_email_format"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_too_many_email_ids_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_invalid_email_request("too_many_emails"))
        assert r.status_code == 400, f"Expected 400 for >50 emails, got {r.status_code}: {r.text}"

    def test_nonexistent_template_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_email_request(f"NONEXISTENT-{_tpl_id()}"))
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  json_body=make_email_request(_tpl_id()))
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"

    def test_invalid_token_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers={"Authorization": "Bearer invalid-token-xyz"},
                  json_body=make_email_request(_tpl_id()))
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"


# ── SMS Send — negative ───────────────────────────────────────────────────────

class TestSMSSendNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers, json_body={})
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_missing_template_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_invalid_sms_request("missing_template_id"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_missing_mobile_numbers_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_invalid_sms_request("missing_mobile_numbers"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_empty_mobile_numbers_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_invalid_sms_request("empty_mobile_numbers"))
        assert r.status_code == 400, f"Expected 400, got {r.status_code}: {r.text}"

    def test_invalid_mobile_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        # Mobile numbers must be in E.164 format (e.g. +919876543210)
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_invalid_sms_request("invalid_mobile_format"))
        assert r.status_code == 400, f"Expected 400 for non-E.164 number, got {r.status_code}: {r.text}"

    def test_too_many_mobile_numbers_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_invalid_sms_request("too_many_mobiles"))
        assert r.status_code == 400, f"Expected 400 for >10 mobiles, got {r.status_code}: {r.text}"

    def test_invalid_category_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_invalid_sms_request("invalid_category"))
        assert r.status_code == 400, f"Expected 400 for invalid category, got {r.status_code}: {r.text}"

    def test_nonexistent_template_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers=auth_headers,
                  json_body=make_sms_request(f"NONEXISTENT-{_tpl_id()}"))
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_error_array(r.json())

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  json_body=make_sms_request(_tpl_id()))
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"

    def test_invalid_token_returns_401(self, request, base_url, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/sms/send",
                  headers={"Authorization": "Bearer invalid-token-xyz"},
                  json_body=make_sms_request(_tpl_id()))
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"
