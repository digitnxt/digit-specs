import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers, assert_error_array
from tests.helpers.factories import (
    make_shorten_request,
    make_invalid_shorten_request,
    make_url_config_request,
    make_invalid_url_config_request,
)

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}
ERROR_ARRAY_SCHEMA = {"type": "array", "items": SINGLE_ERROR_SCHEMA}


def _send(node, method, url, headers=None, json_body=None, allow_redirects=True):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared, allow_redirects=allow_redirects)


class TestShortenNegativeContracts:
    @pytest.fixture(autouse=True)
    def _require_url_config(self, base_url, auth_headers):
        """Ensure a URL config exists before each shorten negative test."""
        r = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        if r.status_code == 404:
            req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                         json={"shortKeyLength": 4, "maxShortKeyRetries": 10})

    def test_shorten_missing_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("missing_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_empty_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("empty_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_invalid_url_format_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("invalid_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_null_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("null_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_url_too_long_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("url_too_long"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_wrong_type_for_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("wrong_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_negative_valid_from_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("negative_valid_from"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_negative_valid_till_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("negative_valid_till"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         json_body=make_shorten_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=bad, json_body=make_shorten_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestRedirectNegativeContracts:
    def test_redirect_nonexistent_key_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/nonexistent-xyz-000",
                         headers=auth_headers, allow_redirects=False)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_redirect_empty_key_returns_404_or_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # A key consisting only of spaces or special chars should fail gracefully
        response = _send(request.node, "GET", f"{base_url}/!!!invalid!!!",
                         headers=auth_headers, allow_redirects=False)
        assert response.status_code in (400, 404), \
            f"Expected 400 or 404 for invalid key, got {response.status_code}"
        assert_gateway_headers(response, gateway_headers_spec)


class TestConfigNegativeContracts:
    def _delete_config(self, base_url, auth_headers):
        req_lib.Session().send(
            req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
        )

    def _create_config(self, base_url, auth_headers):
        req_lib.Session().send(
            req_lib.Request(
                "POST", f"{base_url}/v3/config",
                headers=auth_headers, json=make_url_config_request(),
            ).prepare()
        )

    def test_create_config_missing_required_field_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=auth_headers,
                         json_body=make_invalid_url_config_request("missing_required"))
        assert response.status_code == 400, \
            f"Missing shortKeyLength: expected 400, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_short_key_too_small_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=auth_headers,
                         json_body=make_invalid_url_config_request("key_too_small"))
        assert response.status_code == 400, \
            f"shortKeyLength < 4: expected 400, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_short_key_too_large_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=auth_headers,
                         json_body=make_invalid_url_config_request("key_too_large"))
        assert response.status_code == 400, \
            f"shortKeyLength > 12: expected 400, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_wrong_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=auth_headers,
                         json_body=make_invalid_url_config_request("wrong_type"))
        assert response.status_code == 400, \
            f"shortKeyLength as string: expected 400, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_retries_too_low_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=auth_headers,
                         json_body=make_invalid_url_config_request("retries_too_low"))
        assert response.status_code == 400, \
            f"maxShortKeyRetries < 1: expected 400, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_retries_too_high_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=auth_headers,
                         json_body=make_invalid_url_config_request("retries_too_high"))
        assert response.status_code == 400, \
            f"maxShortKeyRetries > 50: expected 400, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         json_body=make_url_config_request())
        assert response.status_code == 401, \
            f"Missing auth on POST /v3/config: expected 401, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/v3/config",
                         headers=bad, json_body=make_url_config_request())
        assert response.status_code == 401, \
            f"Invalid token on POST /v3/config: expected 401, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_config_duplicate_returns_409(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        self._create_config(base_url, auth_headers)
        try:
            response = _send(request.node, "POST", f"{base_url}/v3/config",
                             headers=auth_headers, json_body=make_url_config_request())
            assert response.status_code == 409, \
                f"Duplicate POST /v3/config: expected 409, got {response.status_code}"
            assert_error_array(response.json())
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            self._delete_config(base_url, auth_headers)

    def test_put_config_not_found_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "PUT", f"{base_url}/v3/config",
                         headers=auth_headers, json_body=make_url_config_request())
        assert response.status_code == 404, \
            f"PUT /v3/config with no existing config: expected 404, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_config_not_found_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "DELETE", f"{base_url}/v3/config",
                         headers=auth_headers)
        assert response.status_code == 404, \
            f"DELETE /v3/config with no config: expected 404, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_config_not_found_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "GET", f"{base_url}/v3/config",
                         headers=auth_headers)
        assert response.status_code == 404, \
            f"GET /v3/config with no config: expected 404, got {response.status_code}"
        assert_error_array(response.json())
        assert_gateway_headers(response, gateway_headers_spec)
