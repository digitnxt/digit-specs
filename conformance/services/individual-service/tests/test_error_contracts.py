"""
Error-path / negative contract tests for the Individual Service.

Covers:
- 400 — validation failures (missing required fields, enum violations,
        format violations, business rules like at-least-one mobile/email).
- 401 — missing or invalid bearer token (gateway-level).
- 404 — get/update/delete on a non-existent or soft-deleted UUID.
- 409 — optimistic-locking version mismatch on PUT.
"""

import uuid
import requests as req_lib

from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_error_response,
)
from tests.helpers.factories import (
    make_individual,
    make_individual_update,
    make_invalid_individual,
    make_invalid_config_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(url, headers):
    try:
        req_lib.Session().send(
            req_lib.Request("DELETE", url, headers=headers).prepare()
        )
    except Exception:
        pass


def _assert_error_body(response):
    """Convenience: every 4xx/5xx body must be a JSON array of Error objects
    per the spec — `[{"code": "...", "message": "...", ...}]`.

    Single-object error bodies (legacy `{...}`) are rejected.
    """
    assert_error_response(response)


# ── 401 — Auth errors (enforced by gateway, not service) ──────────────────────

class TestAuthErrors:
    def test_missing_auth_search_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/individuals")
        assert response.status_code == 401, \
            f"expected 401 without Authorization, got {response.status_code}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_search_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer not-a-real-token"}
        response = _send(request.node, "GET", f"{base_url}/individuals", headers=bad)
        assert response.status_code == 401, \
            f"expected 401 with bad token, got {response.status_code}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_create_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         json_body=make_individual())
        assert response.status_code == 401
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── 400 — Create validation errors ────────────────────────────────────────────

class TestCreateValidationErrors:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400, f"got {response.status_code}: {response.text}"
        assert_json_content_type(response)
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_given_name_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("missing_given_name"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_gender_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("missing_gender"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_mobile_and_email_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """Per spec: at least one of mobileNumber or email must be supplied."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("missing_mobile_and_email"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_empty_given_name_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("empty_given_name"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_given_name_too_long_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """givenName has maxLength 128."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("given_name_too_long"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_gender_enum_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_gender"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_lowercase_gender_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """Gender enum is case-sensitive (MALE/FEMALE/OTHER, not male)."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("lowercase_gender"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_email_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_email"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_dob_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """dateOfBirth must be ISO YYYY-MM-DD."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_dob_format"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_duplicate_identifier_type_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """Per spec: each identifierType may appear at most once per individual."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("duplicate_identifier_type"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_identifier_type_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """identifierType must be in the enum (NATIONAL_ID, AADHAAR, etc.)."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_identifier_type"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_empty_address_entry_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """Per spec: each address must include at least one of doorNo/street/landmark/city."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("empty_address_entry"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_latitude_out_of_range_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """latitude must be in [-90, 90]."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("latitude_out_of_range"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_attribute_key_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """additionalAttributes keys must match ^[a-zA-Z0-9_.-]+$ — spaces disallowed."""
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_attribute_key"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_unknown_field_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """Create decoder uses DisallowUnknownFields — an unknown body field → 400.
        (Sending server-managed/immutable fields like tenantId also hits this.)"""
        body = make_individual(unexpectedField="nope")
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body=body)
        assert response.status_code == 400, f"got {response.status_code}: {response.text}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── 404 — Not-found errors ────────────────────────────────────────────────────

class TestNotFoundErrors:
    def test_get_nonexistent_individual_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_individual_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        # PUT validates `version` (required, ≥1) BEFORE the row-exists check, so
        # a version must be supplied to reach the 404 — omitting it returns 400.
        response = _send(request.node, "PUT",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body=make_individual_update(version=1))
        assert response.status_code == 404
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_individual_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "DELETE",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── 400 — Update validation (version required) ────────────────────────────────

class TestUpdateValidationErrors:
    def test_update_missing_version_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """PUT requires `version` (≥1). The validator checks it before the
        row-exists check, so a random id with no version → 400, not 404."""
        body = make_individual_update()
        body.pop("version", None)
        response = _send(request.node, "PUT",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers, json_body=body)
        assert response.status_code == 400, f"got {response.status_code}: {response.text}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── 409 — Optimistic-locking conflict on PUT ──────────────────────────────────

class TestUpdateConflictErrors:
    def test_stale_version_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        """Per spec: PUT with a `version` that doesn't match current → 409
        ROW_VERSION_MISMATCH. Omitting `version` bypasses optimistic locking."""
        create_r = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body=make_individual())
        if create_r.status_code != 201:
            import pytest
            pytest.skip(f"setup create failed: {create_r.text}")
        ind_id = create_r.json()["id"]
        try:
            stale = make_individual_update(version=999)
            response = _send(request.node, "PUT",
                             f"{base_url}/individuals/{ind_id}",
                             headers=auth_headers, json_body=stale)
            assert response.status_code == 409, \
                f"expected 409 ROW_VERSION_MISMATCH, got {response.status_code}: {response.text}"
            _assert_error_body(response)
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _cleanup(f"{base_url}/individuals/{ind_id}", auth_headers)


# ── 400 — Search / Exists query errors ────────────────────────────────────────

class TestSearchAndExistsErrors:
    def test_search_invalid_gender_enum_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/individuals",
                         headers=auth_headers, params={"gender": "UNKNOWN"})
        assert response.status_code == 400, \
            f"expected 400 for invalid gender enum, got {response.status_code}: {response.text}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_invalid_dob_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/individuals",
                         headers=auth_headers, params={"dateOfBirth": "01-01-1990"})
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_exists_with_no_filter_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """Per spec: at least one filter parameter must be supplied to /exists."""
        response = _send(request.node, "GET", f"{base_url}/individuals/exists",
                         headers=auth_headers)
        assert response.status_code == 400, \
            f"expected 400 when /exists called with no filters, got {response.status_code}: {response.text}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── 400 — Config validation errors ────────────────────────────────────────────

class TestConfigValidationErrors:
    def test_invalid_mobile_regex_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_invalid_config_request("invalid_mobile_regex"))
        assert response.status_code == 400, \
            f"expected 400 for invalid mobileRegex, got {response.status_code}: {response.text}"
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_name_regex_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_invalid_config_request("invalid_name_regex"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_mobile_regex_too_long_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        """mobileRegex has maxLength 512."""
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_invalid_config_request("mobile_regex_too_long"))
        assert response.status_code == 400
        _assert_error_body(response)
        assert_gateway_headers(response, gateway_headers_spec)
