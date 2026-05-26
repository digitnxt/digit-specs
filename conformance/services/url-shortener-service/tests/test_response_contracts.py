import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_service_response_headers,
    assert_json_content_type,
    assert_required_fields,
    assert_field_types,
    assert_shorten_response_shape,
    assert_redirect_response,
    assert_url_config_shape,
    assert_delete_config_shape,
)
from tests.helpers.factories import (
    make_shorten_request,
    make_shorten_request_with_validity,
    extract_key_from_short_url,
    make_url_config_request,
)


def _send(node, method, url, headers=None, json_body=None, allow_redirects=True):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared, allow_redirects=allow_redirects)


class TestShortenContract:
    @pytest.fixture(autouse=True)
    def _require_url_config(self, base_url, auth_headers):
        """Ensure a URL config exists for the tenant before each shorten test."""
        r = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        if r.status_code == 404:
            req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                         json={"shortKeyLength": 4, "maxShortKeyRetries": 10})

    def test_shorten_returns_201_with_short_url(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers, json_body=make_shorten_request())
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_shorten_response_shape(response.json())

    def test_shorten_response_has_required_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers, json_body=make_shorten_request())
        assert response.status_code == 201
        assert_required_fields(response.json(), ["shortUrl"])
        assert_field_types(response.json(), {"shortUrl": str})

    def test_shorten_short_url_is_absolute_uri(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers, json_body=make_shorten_request())
        assert response.status_code == 201
        short_url = response.json()["shortUrl"]
        assert short_url.startswith("http"), \
            f"shortUrl must be an absolute URI, got: {short_url!r}"

    def test_shorten_different_urls_produce_different_keys(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        url_a = f"https://example.com/path-a/{uuid.uuid4().hex}"
        url_b = f"https://example.com/path-b/{uuid.uuid4().hex}"

        r_a = _send(request.node, "POST", f"{base_url}/v3/short-url",
                    headers=auth_headers, json_body=make_shorten_request(url=url_a))
        r_b = _send(request.node, "POST", f"{base_url}/v3/short-url",
                    headers=auth_headers, json_body=make_shorten_request(url=url_b))

        assert r_a.status_code == 201 and r_b.status_code == 201
        key_a = extract_key_from_short_url(r_a.json()["shortUrl"])
        key_b = extract_key_from_short_url(r_b.json()["shortUrl"])
        assert key_a != key_b, "Different URLs must produce different short keys"

    def test_shorten_with_validity_window(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers,
                         json_body=make_shorten_request_with_validity(valid_for_seconds=3600))
        assert response.status_code == 201
        assert_shorten_response_shape(response.json())

    def test_shorten_idempotency_key_accepted(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        idempotency_key = uuid.uuid4().hex
        headers = {**auth_headers, "Idempotency-Key": idempotency_key}
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=headers, json_body=make_shorten_request())
        assert response.status_code == 201
        assert_shorten_response_shape(response.json())

    def test_shorten_same_url_with_same_idempotency_key_is_idempotent(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        """Same Idempotency-Key must return the same shortUrl on repeat."""
        idempotency_key = uuid.uuid4().hex
        headers = {**auth_headers, "Idempotency-Key": idempotency_key}
        payload = make_shorten_request()

        r1 = _send(request.node, "POST", f"{base_url}/v3/short-url",
                   headers=headers, json_body=payload)
        r2 = _send(request.node, "POST", f"{base_url}/v3/short-url",
                   headers=headers, json_body=payload)

        assert r1.status_code == 201
        assert r2.status_code in (200, 201), \
            f"Repeat with same idempotency key expected 200 or 201, got {r2.status_code}"
        assert r1.json()["shortUrl"] == r2.json()["shortUrl"], \
            "Same idempotency key must return the same shortUrl"

    def test_shorten_audit_detail_shape_when_present(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/v3/short-url",
                         headers=auth_headers, json_body=make_shorten_request())
        if response.status_code != 201:
            pytest.skip("Shorten returned non-201")
        body = response.json()
        if "auditDetail" in body:
            audit = body["auditDetail"]
            assert isinstance(audit, dict), "auditDetail must be an object"


class TestRedirectContract:
    def test_redirect_returns_307_with_location(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        original_url = f"https://example.com/redirect-test/{uuid.uuid4().hex}"
        shorten_r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                          headers=auth_headers,
                          json_body=make_shorten_request(url=original_url))
        if shorten_r.status_code != 201:
            pytest.skip("Shorten failed — cannot test redirect")
        key = extract_key_from_short_url(shorten_r.json()["shortUrl"])

        response = _send(request.node, "GET", f"{base_url}/{key}",
                         headers=auth_headers, allow_redirects=False)
        assert_redirect_response(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_redirect_location_matches_original_url(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        original_url = f"https://example.com/location-check/{uuid.uuid4().hex}"
        shorten_r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                          headers=auth_headers,
                          json_body=make_shorten_request(url=original_url))
        if shorten_r.status_code != 201:
            pytest.skip("Shorten failed")
        key = extract_key_from_short_url(shorten_r.json()["shortUrl"])

        response = _send(request.node, "GET", f"{base_url}/{key}",
                         headers=auth_headers, allow_redirects=False)
        assert response.status_code == 307
        assert response.headers.get("Location") == original_url, \
            f"Location header '{response.headers.get('Location')}' != original URL '{original_url}'"

    def test_nonexistent_key_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/nonexistent-key-xyz-000",
                         headers=auth_headers, allow_redirects=False)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestConfigContract:
    def _delete_config(self, base_url, auth_headers):
        """Best-effort cleanup — delete config if it exists."""
        req_lib.Session().send(
            req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
        )

    def _create_config(self, base_url, auth_headers, payload=None):
        return req_lib.Session().send(
            req_lib.Request(
                "POST", f"{base_url}/v3/config",
                headers=auth_headers, json=payload or make_url_config_request(),
            ).prepare()
        )

    def test_post_config_returns_201_with_url_config_shape(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        try:
            response = _send(request.node, "POST", f"{base_url}/v3/config",
                             headers=auth_headers, json_body=make_url_config_request())
            assert response.status_code == 201, \
                f"POST /v3/config expected 201, got {response.status_code}: {response.text}"
            assert_service_response_headers(response)
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
            assert_url_config_shape(response.json())
        finally:
            self._delete_config(base_url, auth_headers)

    def test_post_config_persists_short_key_length(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        try:
            response = _send(request.node, "POST", f"{base_url}/v3/config",
                             headers=auth_headers,
                             json_body=make_url_config_request(short_key_length=8))
            assert response.status_code == 201
            assert response.json()["shortKeyLength"] == 8, \
                f"shortKeyLength should be 8, got {response.json().get('shortKeyLength')}"
        finally:
            self._delete_config(base_url, auth_headers)

    def test_get_config_returns_200_with_url_config_shape(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        self._create_config(base_url, auth_headers)
        try:
            response = _send(request.node, "GET", f"{base_url}/v3/config",
                             headers=auth_headers)
            assert response.status_code == 200, \
                f"GET /v3/config expected 200, got {response.status_code}: {response.text}"
            assert_service_response_headers(response)
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
            assert_url_config_shape(response.json())
        finally:
            self._delete_config(base_url, auth_headers)

    def test_put_config_returns_200_with_updated_shape(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        self._create_config(base_url, auth_headers, make_url_config_request(short_key_length=4))
        try:
            response = _send(request.node, "PUT", f"{base_url}/v3/config",
                             headers=auth_headers,
                             json_body=make_url_config_request(short_key_length=6))
            assert response.status_code == 200, \
                f"PUT /v3/config expected 200, got {response.status_code}: {response.text}"
            assert_service_response_headers(response)
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
            assert_url_config_shape(response.json())
            assert response.json()["shortKeyLength"] == 6, \
                f"PUT should update shortKeyLength to 6, got {response.json().get('shortKeyLength')}"
        finally:
            self._delete_config(base_url, auth_headers)

    def test_delete_config_returns_200_with_deleted_flag(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        self._create_config(base_url, auth_headers)
        response = _send(request.node, "DELETE", f"{base_url}/v3/config",
                         headers=auth_headers)
        assert response.status_code == 200, \
            f"DELETE /v3/config expected 200, got {response.status_code}: {response.text}"
        assert_service_response_headers(response)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_delete_config_shape(response.json())

    def test_post_config_duplicate_returns_409(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        self._create_config(base_url, auth_headers)
        try:
            response = _send(request.node, "POST", f"{base_url}/v3/config",
                             headers=auth_headers, json_body=make_url_config_request())
            assert response.status_code == 409, \
                f"Duplicate POST /v3/config expected 409, got {response.status_code}: {response.text}"
        finally:
            self._delete_config(base_url, auth_headers)

    def test_put_config_not_found_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "PUT", f"{base_url}/v3/config",
                         headers=auth_headers, json_body=make_url_config_request())
        assert response.status_code == 404, \
            f"PUT /v3/config with no existing config expected 404, got {response.status_code}"

    def test_get_config_not_found_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        self._delete_config(base_url, auth_headers)
        response = _send(request.node, "GET", f"{base_url}/v3/config",
                         headers=auth_headers)
        assert response.status_code == 404, \
            f"GET /v3/config with no config expected 404, got {response.status_code}"
