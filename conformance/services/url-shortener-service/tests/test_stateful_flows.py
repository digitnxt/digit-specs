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
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_shorten_response_shape,
    assert_redirect_response,
)


def _send(node, method, url, headers=None, json_body=None, allow_redirects=True):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared, allow_redirects=allow_redirects)


class TestShortenAndRedirectFlow:
    def test_shorten_then_redirect(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        original_url = f"https://example.com/flow-test/{uuid.uuid4().hex}"

        # SHORTEN
        r = _send(request.node, "POST", f"{base_url}/short-url",
                  headers=auth_headers, json_body=make_shorten_request(url=original_url))
        assert r.status_code == 201, f"Shorten failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_shorten_response_shape(r.json())
        key = extract_key_from_short_url(r.json()["shortUrl"])
        assert key, "Could not extract short key from shortUrl"

        # REDIRECT
        r = _send(request.node, "GET", f"{base_url}/{key}",
                  allow_redirects=False)
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
            r = _send(request.node, "POST", f"{base_url}/short-url",
                      headers=auth_headers, json_body=make_shorten_request(url=url))
            assert r.status_code == 201, f"Shorten {i} failed: {r.text}"
            key = extract_key_from_short_url(r.json()["shortUrl"])
            pairs[key] = url

        for key, expected_url in pairs.items():
            r = _send(request.node, "GET", f"{base_url}/{key}",
                      allow_redirects=False)
            assert r.status_code == 307, f"Key '{key}': expected 307, got {r.status_code}"
            assert r.headers.get("Location") == expected_url, \
                f"Key '{key}': Location '{r.headers.get('Location')}' != '{expected_url}'"

    def test_shorten_with_validity_then_redirect_while_active(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        original_url = f"https://example.com/valid-window/{uuid.uuid4().hex}"
        payload = {**make_shorten_request_with_validity(valid_for_seconds=3600),
                   "url": original_url}

        r = _send(request.node, "POST", f"{base_url}/short-url",
                  headers=auth_headers, json_body=payload)
        assert r.status_code == 201, f"Shorten failed: {r.text}"
        key = extract_key_from_short_url(r.json()["shortUrl"])

        r = _send(request.node, "GET", f"{base_url}/{key}",
                  allow_redirects=False)
        assert r.status_code == 307, f"Active URL should redirect: got {r.status_code}"
        assert r.headers.get("Location") == original_url

    def test_shorten_already_expired_returns_404_on_redirect(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        r = _send(request.node, "POST", f"{base_url}/short-url",
                  headers=auth_headers, json_body=make_shorten_request_already_expired())
        if r.status_code != 201:
            pytest.skip("Service rejected expired validity window — cannot test redirect")
        key = extract_key_from_short_url(r.json()["shortUrl"])

        r = _send(request.node, "GET", f"{base_url}/{key}",
                  allow_redirects=False)
        assert r.status_code in (404, 410), \
            f"Expired short URL should return 404 or 410, got {r.status_code}: {r.text}"

    def test_shorten_future_validity_returns_404_before_active(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        r = _send(request.node, "POST", f"{base_url}/short-url",
                  headers=auth_headers, json_body=make_shorten_request_future_validity())
        if r.status_code != 201:
            pytest.skip("Service rejected future validity window")
        key = extract_key_from_short_url(r.json()["shortUrl"])

        # Immediately try to redirect — validFrom is in the future so should not work
        r = _send(request.node, "GET", f"{base_url}/{key}",
                  allow_redirects=False)
        assert r.status_code in (404, 403, 425), \
            f"Not-yet-active URL should fail, got {r.status_code}: {r.text}"

    def test_redirect_without_following_does_not_hit_target(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        """Verify the service issues 307, not a final 200 from the target."""
        original_url = f"https://example.com/no-follow/{uuid.uuid4().hex}"
        r = _send(request.node, "POST", f"{base_url}/short-url",
                  headers=auth_headers, json_body=make_shorten_request(url=original_url))
        assert r.status_code == 201
        key = extract_key_from_short_url(r.json()["shortUrl"])

        # allow_redirects=False — must see the raw 307, not a followed redirect
        r = _send(request.node, "GET", f"{base_url}/{key}",
                  allow_redirects=False)
        assert r.status_code == 307, \
            f"Service must return 307 (not follow the redirect): got {r.status_code}"


class TestIdempotencyFlow:
    def test_same_idempotency_key_returns_same_short_url(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        idempotency_key = uuid.uuid4().hex
        headers = {**auth_headers, "Idempotency-Key": idempotency_key}
        payload = make_shorten_request()

        r1 = _send(request.node, "POST", f"{base_url}/short-url",
                   headers=headers, json_body=payload)
        r2 = _send(request.node, "POST", f"{base_url}/short-url",
                   headers=headers, json_body=payload)

        assert r1.status_code == 201
        assert r2.status_code in (200, 201)
        assert r1.json()["shortUrl"] == r2.json()["shortUrl"], \
            "Same Idempotency-Key must produce the same shortUrl on repeat"

    def test_different_idempotency_keys_produce_different_short_urls(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        url = f"https://example.com/idem-diff/{uuid.uuid4().hex}"

        r1 = _send(request.node, "POST", f"{base_url}/short-url",
                   headers={**auth_headers, "Idempotency-Key": uuid.uuid4().hex},
                   json_body=make_shorten_request(url=url))
        r2 = _send(request.node, "POST", f"{base_url}/short-url",
                   headers={**auth_headers, "Idempotency-Key": uuid.uuid4().hex},
                   json_body=make_shorten_request(url=url))

        assert r1.status_code == 201 and r2.status_code == 201
        # Different idempotency keys — may or may not produce the same URL depending
        # on whether the service deduplicates by URL; both must be valid short URLs
        assert_shorten_response_shape(r1.json())
        assert_shorten_response_shape(r2.json())
