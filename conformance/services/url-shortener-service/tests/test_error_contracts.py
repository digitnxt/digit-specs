import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers
from tests.helpers.factories import (
    make_shorten_request,
    make_invalid_shorten_request,
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
    def test_shorten_missing_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("missing_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_empty_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("empty_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_invalid_url_format_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("invalid_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_null_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("null_url"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_url_too_long_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("url_too_long"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_wrong_type_for_url_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("wrong_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_negative_valid_from_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("negative_valid_from"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_negative_valid_till_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=auth_headers,
                         json_body=make_invalid_shorten_request("negative_valid_till"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         json_body=make_shorten_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_shorten_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/short-url",
                         headers=bad, json_body=make_shorten_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestRedirectNegativeContracts:
    def test_redirect_nonexistent_key_returns_404(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/nonexistent-xyz-000",
                         allow_redirects=False)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_redirect_empty_key_returns_404_or_400(
        self, request, base_url, gateway_headers_spec
    ):
        # A key consisting only of spaces or special chars should fail gracefully
        response = _send(request.node, "GET", f"{base_url}/!!!invalid!!!",
                         allow_redirects=False)
        assert response.status_code in (400, 404), \
            f"Expected 400 or 404 for invalid key, got {response.status_code}"
        assert_gateway_headers(response, gateway_headers_spec)
