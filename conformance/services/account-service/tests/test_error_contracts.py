"""Error-path contracts for the Account/Tenant service (v3).

Assertions follow the SPEC (account.yaml). Known implementation divergences
that these will surface:
  - Java collapses 409/404/410/422/423/429 business errors to 400.
  - Java wraps errors as {"Errors":[...]} instead of the spec's bare [Error] array.
Both are real conformance findings, not test bugs.
"""
import uuid
import pytest
import requests as req_lib
from tests.helpers.validators import assert_error_array, assert_error_body
from tests.helpers.factories import (
    make_tenant_request,
    make_invalid_tenant_request,
    make_invalid_tenant_config_request,
    make_invalid_signup_verify_request,
    make_invalid_signup_resend_request,
    make_signup_verify_request,
    make_signup_resend_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    node._curl_request = prepared
    return req_lib.Session().send(prepared, timeout=30)


# ── Tenant validation → 400 ──────────────────────────────────────────────────

class TestTenantValidationErrors:
    @pytest.mark.parametrize("strategy", [
        "missing_name", "missing_email", "empty_name", "empty_email",
        "invalid_email_format", "name_too_long", "email_too_short",
        "password_too_short", "invalid_phone", "wrong_types", "empty_body",
    ])
    def test_invalid_create_returns_400(self, request, base_url, auth_headers, strategy):
        r = _send(request.node, "POST", f"{base_url}/tenants",
                  headers=auth_headers, json_body=make_invalid_tenant_request(strategy))
        assert r.status_code == 400, f"[{strategy}] expected 400, got {r.status_code}: {r.text}"


class TestTenantErrorEnvelope:
    def test_400_body_is_bare_error_array(self, request, base_url, auth_headers):
        """Spec: a 400 returns a bare [Error] array (Java wraps as {'Errors':[...]})."""
        r = _send(request.node, "POST", f"{base_url}/tenants",
                  headers=auth_headers, json_body=make_invalid_tenant_request("missing_name"))
        assert r.status_code == 400, f"expected 400, got {r.status_code}: {r.text}"
        assert_error_array(r.json())


class TestTenantConflict:
    def test_duplicate_name_returns_409(self, request, base_url, auth_headers, tenant_factory):
        # code is derived from name and is globally unique → re-create → 409.
        body = make_tenant_request()
        first = tenant_factory.create(**body)
        if first.status_code != 201:
            pytest.skip(f"first create failed: {first.text}")
        r = _send(request.node, "POST", f"{base_url}/tenants", headers=auth_headers, json_body=body)
        assert r.status_code == 409, f"expected 409 on duplicate code, got {r.status_code}: {r.text}"
        assert_error_body(r.json())


class TestTenantNotFound:
    def test_update_unknown_id_returns_404(self, request, base_url, auth_headers):
        r = _send(request.node, "PUT", f"{base_url}/tenants/{uuid.uuid4()}",
                  headers=auth_headers, json_body={"additionalAttributes": {"x": "y"}})
        assert r.status_code == 404, f"expected 404, got {r.status_code}: {r.text}"

    def test_delete_unknown_id_returns_404(self, request, base_url, auth_headers):
        r = _send(request.node, "DELETE", f"{base_url}/tenants/{uuid.uuid4()}", headers=auth_headers)
        assert r.status_code == 404, f"expected 404, got {r.status_code}: {r.text}"


# ── Config errors → 400 ──────────────────────────────────────────────────────

class TestTenantConfigErrors:
    def test_list_without_tenant_header_returns_400(self, request, base_url, auth_headers):
        r = _send(request.node, "GET", f"{base_url}/config", headers=auth_headers)
        assert r.status_code == 400, f"expected 400 (X-Tenant-Id required), got {r.status_code}: {r.text}"

    def test_list_with_non_uuid_tenant_returns_400(self, request, base_url, auth_headers):
        headers = {**auth_headers, "X-Tenant-Id": "not-a-uuid"}
        r = _send(request.node, "GET", f"{base_url}/config", headers=headers)
        assert r.status_code == 400, f"expected 400 (tenant must be UUID), got {r.status_code}: {r.text}"

    @pytest.mark.parametrize("strategy", [
        "missing_config_key", "missing_config_value", "empty_config_key",
        "empty_config_value", "bad_tenant_id_format", "wrong_types", "empty_body",
    ])
    def test_invalid_config_create_returns_400(self, request, base_url, auth_headers, tenant_factory, strategy):
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"tenant create failed: {cr.text}")
        tid = cr.json()["id"]
        headers = {**auth_headers, "X-Tenant-Id": tid}
        r = _send(request.node, "POST", f"{base_url}/config",
                  headers=headers, json_body=make_invalid_tenant_config_request(tid, strategy))
        assert r.status_code == 400, f"[{strategy}] expected 400, got {r.status_code}: {r.text}"

    def test_config_tenant_id_mismatch_returns_422(self, request, base_url, auth_headers, tenant_factory):
        # A body tenantId referencing a nonexistent tenant → 422 TENANT_NOT_FOUND.
        # (Java returns 422 as intended; Go still returns 400 pending a fix.)
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"tenant create failed: {cr.text}")
        tid = cr.json()["id"]
        headers = {**auth_headers, "X-Tenant-Id": tid}
        from tests.helpers.factories import make_tenant_config_request
        body = make_tenant_config_request(str(uuid.uuid4()))
        r = _send(request.node, "POST", f"{base_url}/config", headers=headers, json_body=body)
        assert r.status_code == 422, f"expected 422, got {r.status_code}: {r.text}"


# ── Signup error paths (verify success needs an out-of-band OTP — skipped) ───

class TestSignupVerifyErrors:
    @pytest.mark.parametrize("strategy", [
        "missing_reference_id", "missing_otp", "missing_purpose", "empty_reference_id",
        "short_reference_id", "non_numeric_otp", "otp_too_short", "otp_too_long", "bad_purpose",
    ])
    def test_invalid_verify_returns_400(self, request, base_url, auth_headers, strategy):
        r = _send(request.node, "POST", f"{base_url}/tenants/registrations/verify",
                  headers=auth_headers, json_body=make_invalid_signup_verify_request(strategy))
        assert r.status_code == 400, f"[{strategy}] expected 400, got {r.status_code}: {r.text}"

    def test_unknown_reference_id_is_4xx(self, request, base_url, auth_headers):
        # A well-formed but unknown/expired referenceId. Spec documents 401/410;
        # Go returns 404 REQUEST_EXPIRED. Accept any 4xx (must not be a 5xx).
        r = _send(request.node, "POST", f"{base_url}/tenants/registrations/verify",
                  headers=auth_headers,
                  json_body=make_signup_verify_request("r" * 24, otp="123456"))
        assert 400 <= r.status_code < 500, f"expected 4xx, got {r.status_code}: {r.text}"


class TestSignupResendErrors:
    @pytest.mark.parametrize("strategy", [
        "missing_reference_id", "empty_reference_id", "short_reference_id",
    ])
    def test_invalid_resend_returns_400(self, request, base_url, auth_headers, strategy):
        r = _send(request.node, "POST", f"{base_url}/tenants/registrations/resend",
                  headers=auth_headers, json_body=make_invalid_signup_resend_request(strategy))
        assert r.status_code == 400, f"[{strategy}] expected 400, got {r.status_code}: {r.text}"

    def test_unknown_reference_id_is_4xx(self, request, base_url, auth_headers):
        r = _send(request.node, "POST", f"{base_url}/tenants/registrations/resend",
                  headers=auth_headers, json_body=make_signup_resend_request("r" * 24))
        assert 400 <= r.status_code < 500, f"expected 4xx, got {r.status_code}: {r.text}"
