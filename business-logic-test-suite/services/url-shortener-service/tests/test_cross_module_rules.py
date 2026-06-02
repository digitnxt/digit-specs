"""
Cross-module rule tests for URL Shortener service.
Cache and PubSub rules are internal; only externally observable behaviors are tested.
"""
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CM-001: Cache fallthrough is transparent
# ---------------------------------------------------------------------------

class TestBR_CM_001_cache_fallthrough_is_transparent:
    """Cache miss falls through to DB without error; caller sees no difference."""

    def test_newly_created_url_resolves_successfully(self, request, base_url, auth_headers):
        shorten = _post(request.node, f"{base_url}/short-url", auth_headers, {
            "url": "https://example.com/cm001-cache",
        })
        assert shorten.status_code == 201, f"Shorten failed: {shorten.text}"
        short_key = shorten.json().get("shortUrl", "").rstrip("/").split("/")[-1]

        resolve = req_lib.get(f"{base_url}/{short_key}", headers=auth_headers,
                              allow_redirects=False)
        assert resolve.status_code in (200, 301, 302, 307), \
            f"Newly created URL must resolve (cache fallthrough): {resolve.status_code}"

    def test_unknown_short_key_returns_404(self, request, base_url, auth_headers):
        resolve = req_lib.get(f"{base_url}/NONEXISTENTKEY", headers=auth_headers,
                              allow_redirects=False)
        assert resolve.status_code == 404, \
            f"Unknown key should return 404, got {resolve.status_code}"


# ---------------------------------------------------------------------------
# BR-CM-002: PubSub publish is fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_002_pubsub_publish_is_fire_and_forget:
    """Shorten and config operations succeed regardless of PubSub availability."""

    def test_url_creation_returns_201_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/short-url", auth_headers, {
            "url": "https://example.com/cm002-pubsub",
        })
        assert resp.status_code == 201, \
            f"URL creation must succeed regardless of PubSub, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CM-003: SERVER_HOST_NAME validated at startup
# ---------------------------------------------------------------------------

class TestBR_CM_003_server_host_name_validated_at_startup:
    """
    SERVICE_HOST_NAME is validated when the service starts. Observable only indirectly
    by verifying the service is reachable (i.e., it started successfully with a valid config).
    """

    def test_service_is_reachable_indicating_valid_startup_config(
        self, request, base_url, auth_headers
    ):
        resp = req_lib.get(f"{base_url}/config", headers=auth_headers)
        assert resp.status_code in (200, 404), \
            f"Service must be reachable (valid SERVER_HOST_NAME at startup), got {resp.status_code}"
