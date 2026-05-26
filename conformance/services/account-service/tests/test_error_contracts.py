import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers
from tests.helpers.factories import (
    make_tenant_request,
    make_tenant_config_request,
    make_invalid_tenant_request,
    make_invalid_tenant_config_request,
    make_invalid_signup_verify_request,
    make_invalid_signup_resend_request,
    make_missing_tenant_envelope,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── Tenant auth errors ─────────────────────────────────────────────────────

class TestTenantAuthErrors:
    def test_create_tenant_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         json_body=make_tenant_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=bad, json_body=make_tenant_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_list_tenants_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/accounts")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_tenant_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "PUT",
                         f"{base_url}/accounts/00000000-0000-0000-0000-000000000000",
                         json_body=make_tenant_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── Tenant validation errors ───────────────────────────────────────────────

class TestTenantValidationErrors:
    def test_create_tenant_missing_name_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("missing_name"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_missing_email_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("missing_email"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_invalid_email_format_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("invalid_email_format"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_empty_name_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("empty_name"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_invalid_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # `code` must match ^[A-Z0-9]+$ — lowercase + special chars violate it
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("invalid_code"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_wrong_types_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("wrong_types"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_email_too_short_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # email has minLength: 5 — "a@b" is 3 chars
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("email_too_short"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_name_too_long_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # name has maxLength: 128 — payload sends 129 chars
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("name_too_long"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_password_too_short_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # password has minLength: 8 when supplied
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("password_too_short"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_missing_envelope_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # TenantRequest requires the `tenant` envelope — without it the
        # service should reject the body.
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_missing_tenant_envelope())
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_tenant_empty_envelope_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # {"tenant": {}} — envelope is present but tenant has no fields
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("empty_tenant_envelope"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)


# ── Tenant duplicate / conflict ────────────────────────────────────────────

class TestTenantDuplicateErrors:
    def test_create_duplicate_code_returns_400_or_409(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Per spec: 400 carries DUPLICATE_RECORD if the same code already
        # exists; PUT surfaces 409 for the same constraint on update. POST
        # tolerates either status here (some deployments map duplicate to 409).
        body = make_tenant_request()
        first = _send(request.node, "POST", f"{base_url}/accounts",
                      headers=auth_headers, json_body=body)
        if first.status_code != 201:
            pytest.skip(f"first create failed, cannot test duplicate: {first.text}")

        second = _send(request.node, "POST", f"{base_url}/accounts",
                       headers=auth_headers, json_body=body)
        assert second.status_code in (400, 409), \
            f"expected 400/409 for duplicate, got {second.status_code}: {second.text}"
        assert_gateway_headers(second, gateway_headers_spec)


# ── Account delete error contracts (auth only — destructive on success) ────

class TestAccountDeleteAuthErrors:
    """Only auth-error variants are exercised; running DELETE with a valid
    token would wipe the tenant's realm and cascade failures across the
    rest of the suite. See test_schema_conformance._SKIP_ENDPOINTS."""

    def test_delete_account_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/accounts",
                         params={"tenantCode": "DOESNOTMATTER"})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── Signup validation errors ───────────────────────────────────────────────

class TestSignupValidationErrors:
    def test_signup_missing_tenant_envelope_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/signup",
                         headers=auth_headers,
                         json_body=make_missing_tenant_envelope())
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_signup_missing_email_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/signup",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_request("missing_email"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)


# ── Signup verify validation errors (no real OTP needed for shape checks) ──

class TestSignupVerifyValidationErrors:
    def test_verify_missing_reference_id_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/signup/verify",
                         headers=auth_headers,
                         json_body=make_invalid_signup_verify_request("missing_reference_id"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_verify_missing_otp_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/signup/verify",
                         headers=auth_headers,
                         json_body=make_invalid_signup_verify_request("missing_otp"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_verify_non_numeric_otp_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # OTP must match ^[0-9]+$ — alphabetic should be rejected
        response = _send(request.node, "POST", f"{base_url}/signup/verify",
                         headers=auth_headers,
                         json_body=make_invalid_signup_verify_request("non_numeric_otp"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_verify_unknown_reference_id_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Well-formed referenceId that doesn't exist in Redis → REQUEST_EXPIRED 404
        body = {"referenceId": "00000000-0000-0000-0000-000000000000", "otp": "123456"}
        response = _send(request.node, "POST", f"{base_url}/signup/verify",
                         headers=auth_headers, json_body=body)
        # Server may also return 400 for malformed/expired; accept either.
        assert response.status_code in (400, 401, 404), \
            f"expected 400/401/404 for unknown referenceId, got {response.status_code}: {response.text}"
        assert_gateway_headers(response, gateway_headers_spec)


# ── Signup resend validation errors ────────────────────────────────────────

class TestSignupResendValidationErrors:
    def test_resend_missing_reference_id_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/signup/resend",
                         headers=auth_headers,
                         json_body=make_invalid_signup_resend_request("missing_reference_id"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_resend_unknown_reference_id_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        body = {"referenceId": "00000000-0000-0000-0000-000000000000"}
        response = _send(request.node, "POST", f"{base_url}/signup/resend",
                         headers=auth_headers, json_body=body)
        assert response.status_code in (400, 404), \
            f"expected 400/404 for unknown referenceId, got {response.status_code}: {response.text}"
        assert_gateway_headers(response, gateway_headers_spec)


# ── TenantConfig validation errors ─────────────────────────────────────────

class TestTenantConfigValidationErrors:
    def test_create_config_missing_envelope_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_config_request("missing_tenant_envelope"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_unknown_tenant_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Config tied to a tenant code that doesn't exist → TENANT_NOT_FOUND 400
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_tenant_config_request(tenant_code="DOESNOTEXIST"))
        assert response.status_code in (400, 422), \
            f"expected 400/422 for unknown tenant code, got {response.status_code}: {response.text}"
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_invalid_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_config_request("invalid_code"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_otp_length_not_in_enum_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # otpLength must match enum ['4', '6', '8'] — anything else is invalid.
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_config_request("otp_length_not_in_enum"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_otp_length_non_numeric_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_config_request("otp_length_letters"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_language_code_too_short_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Each languages[] item has minLength: 2 — single-char codes violate it.
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_config_request("language_code_too_short"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_language_code_too_long_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_invalid_tenant_config_request("language_code_too_long"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/config",
                         json_body=make_tenant_config_request(tenant_code="DOESNOTMATTER"))
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── TenantConfig not-found errors ──────────────────────────────────────────

class TestTenantConfigNotFoundErrors:
    def test_update_nonexistent_config_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(
            request.node, "PUT",
            f"{base_url}/config/00000000-0000-0000-0000-000000000000",
            headers=auth_headers,
            json_body=make_tenant_config_request(tenant_code="DOESNOTMATTER"),
        )
        # Spec says 404 RECORD_NOT_FOUND; some validation paths can also
        # surface 400 before the lookup runs.
        assert response.status_code in (400, 404), \
            f"expected 400/404 for unknown config id, got {response.status_code}: {response.text}"
        assert_gateway_headers(response, gateway_headers_spec)
