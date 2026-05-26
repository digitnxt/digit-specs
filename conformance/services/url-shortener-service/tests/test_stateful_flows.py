import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_shorten_request,
    make_shorten_request_with_validity,
    make_shorten_request_already_expired,
    make_shorten_request_future_validity,
    extract_key_from_short_url,
    make_url_config_request,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_shorten_response_shape,
    assert_redirect_response,
    assert_url_config_shape,
    assert_delete_config_shape,
)


def _send(node, method, url, headers=None, json_body=None, allow_redirects=True):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared, allow_redirects=allow_redirects)


class TestShortenAndRedirectFlow:
    @pytest.fixture(autouse=True)
    def _require_url_config(self, base_url, auth_headers):
        """Ensure a URL config exists before each shorten+redirect flow test."""
        r = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        if r.status_code == 404:
            req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                         json={"shortKeyLength": 4, "maxShortKeyRetries": 10})

    def test_shorten_then_redirect(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        original_url = f"https://example.com/flow-test/{uuid.uuid4().hex}"

        # SHORTEN
        r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                  headers=auth_headers, json_body=make_shorten_request(url=original_url))
        assert r.status_code == 201, f"Shorten failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_shorten_response_shape(r.json())
        key = extract_key_from_short_url(r.json()["shortUrl"])
        assert key, "Could not extract short key from shortUrl"

        # REDIRECT
        r = _send(request.node, "GET", f"{base_url}/{key}",
                  headers=auth_headers, allow_redirects=False)
        assert_redirect_response(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert r.headers["Location"] == original_url, \
            f"Location '{r.headers['Location']}' does not match original '{original_url}'"

    def test_shorten_multiple_urls_each_redirects_correctly(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        pairs = {}
        for i in range(3):
            url = f"https://example.com/batch/{i}/{uuid.uuid4().hex}"
            r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                      headers=auth_headers, json_body=make_shorten_request(url=url))
            assert r.status_code == 201, f"Shorten {i} failed: {r.text}"
            key = extract_key_from_short_url(r.json()["shortUrl"])
            pairs[key] = url

        for key, expected_url in pairs.items():
            r = _send(request.node, "GET", f"{base_url}/{key}",
                      headers=auth_headers, allow_redirects=False)
            assert r.status_code == 307, f"Key '{key}': expected 307, got {r.status_code}"
            assert r.headers.get("Location") == expected_url, \
                f"Key '{key}': Location '{r.headers.get('Location')}' != '{expected_url}'"

    def test_shorten_with_validity_then_redirect_while_active(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        original_url = f"https://example.com/valid-window/{uuid.uuid4().hex}"
        payload = {**make_shorten_request_with_validity(valid_for_seconds=3600),
                   "url": original_url}

        r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                  headers=auth_headers, json_body=payload)
        assert r.status_code == 201, f"Shorten failed: {r.text}"
        key = extract_key_from_short_url(r.json()["shortUrl"])

        r = _send(request.node, "GET", f"{base_url}/{key}",
                  headers=auth_headers, allow_redirects=False)
        assert r.status_code == 307, f"Active URL should redirect: got {r.status_code}"
        assert r.headers.get("Location") == original_url

    def test_shorten_already_expired_returns_404_on_redirect(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                  headers=auth_headers, json_body=make_shorten_request_already_expired())
        if r.status_code != 201:
            pytest.skip("Service rejected expired validity window — cannot test redirect")
        key = extract_key_from_short_url(r.json()["shortUrl"])

        r = _send(request.node, "GET", f"{base_url}/{key}",
                  headers=auth_headers, allow_redirects=False)
        assert r.status_code in (404, 410), \
            f"Expired short URL should return 404 or 410, got {r.status_code}: {r.text}"

    def test_shorten_future_validity_returns_404_before_active(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                  headers=auth_headers, json_body=make_shorten_request_future_validity())
        if r.status_code != 201:
            pytest.skip("Service rejected future validity window")
        key = extract_key_from_short_url(r.json()["shortUrl"])

        # Immediately try to redirect — validFrom is in the future so should not work
        r = _send(request.node, "GET", f"{base_url}/{key}",
                  headers=auth_headers, allow_redirects=False)
        assert r.status_code in (400, 403, 404, 425), \
            f"Not-yet-active URL should fail, got {r.status_code}: {r.text}"

    def test_redirect_without_following_does_not_hit_target(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        """Verify the service issues 307, not a final 200 from the target."""
        original_url = f"https://example.com/no-follow/{uuid.uuid4().hex}"
        r = _send(request.node, "POST", f"{base_url}/v3/short-url",
                  headers=auth_headers, json_body=make_shorten_request(url=original_url))
        assert r.status_code == 201
        key = extract_key_from_short_url(r.json()["shortUrl"])

        # allow_redirects=False — must see the raw 307, not a followed redirect
        r = _send(request.node, "GET", f"{base_url}/{key}",
                  headers=auth_headers, allow_redirects=False)
        assert r.status_code == 307, \
            f"Service must return 307 (not follow the redirect): got {r.status_code}"


class TestConfigLifecycleFlow:
    def test_create_read_update_delete_config(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Clean slate
        req_lib.Session().send(
            req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
        )
        try:
            # CREATE
            r = _send(request.node, "POST", f"{base_url}/v3/config",
                      headers=auth_headers, json_body=make_url_config_request(short_key_length=4))
            assert r.status_code == 201, f"Create config failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_url_config_shape(r.json())
            assert r.json()["shortKeyLength"] == 4

            # READ — verify persisted data matches what was created
            r = _send(request.node, "GET", f"{base_url}/v3/config", headers=auth_headers)
            assert r.status_code == 200, f"Get config failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_url_config_shape(r.json())
            assert r.json()["shortKeyLength"] == 4, \
                f"GET returned shortKeyLength {r.json()['shortKeyLength']}, expected 4"

            # UPDATE
            r = _send(request.node, "PUT", f"{base_url}/v3/config",
                      headers=auth_headers, json_body=make_url_config_request(short_key_length=8))
            assert r.status_code == 200, f"Update config failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_url_config_shape(r.json())
            assert r.json()["shortKeyLength"] == 8, \
                f"PUT returned shortKeyLength {r.json()['shortKeyLength']}, expected 8"

            # READ AGAIN — confirm update was persisted
            r = _send(request.node, "GET", f"{base_url}/v3/config", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["shortKeyLength"] == 8, \
                f"After PUT, GET returned shortKeyLength {r.json()['shortKeyLength']}, expected 8"

            # DELETE
            r = _send(request.node, "DELETE", f"{base_url}/v3/config", headers=auth_headers)
            assert r.status_code == 200, f"Delete config failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_delete_config_shape(r.json())
            assert r.json()["deleted"] is True

            # VERIFY GONE — GET after delete must return 404
            r = _send(request.node, "GET", f"{base_url}/v3/config", headers=auth_headers)
            assert r.status_code == 404, \
                f"GET after DELETE expected 404, got {r.status_code}"

        except Exception:
            # Best-effort cleanup on failure so other tests start clean
            req_lib.Session().send(
                req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
            )
            raise

    def test_second_post_after_delete_succeeds(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        req_lib.Session().send(
            req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
        )
        try:
            r1 = _send(request.node, "POST", f"{base_url}/v3/config",
                       headers=auth_headers, json_body=make_url_config_request(short_key_length=4))
            assert r1.status_code == 201, f"First POST failed: {r1.text}"

            req_lib.Session().send(
                req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
            )

            r2 = _send(request.node, "POST", f"{base_url}/v3/config",
                       headers=auth_headers, json_body=make_url_config_request(short_key_length=6))
            assert r2.status_code == 201, \
                f"POST after DELETE expected 201, got {r2.status_code}: {r2.text}"
            assert_url_config_shape(r2.json())
        finally:
            req_lib.Session().send(
                req_lib.Request("DELETE", f"{base_url}/v3/config", headers=auth_headers).prepare()
            )
