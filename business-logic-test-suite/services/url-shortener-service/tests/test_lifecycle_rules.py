"""
Lifecycle rule tests for URL Shortener service.
"""
import time
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _get(node, url, headers, params=None):
    r = req_lib.Request("GET", url, headers=headers, params=params)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _now_ms():
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------
# BR-LC-001: Validity window evaluated at redirect time
# ---------------------------------------------------------------------------

class TestBR_LC_001_validity_window_evaluated_at_redirect_time:
    """validFrom and validTill are checked at redirect, not at shorten time."""

    def test_url_with_future_valid_from_returns_400_before_window(
        self, request, base_url, auth_headers
    ):
        valid_from = _now_ms() + 10_000  # 10 seconds in the future

        shorten = req_lib.post(
            f"{base_url}/short-url", headers=auth_headers,
            json={"url": "https://example.com/lc001-future", "validFrom": valid_from},
        )
        assert shorten.status_code == 201, f"Shorten failed: {shorten.text}"
        short_key = shorten.json().get("shortUrl", "").rstrip("/").split("/")[-1]

        resolve = req_lib.get(f"{base_url}/{short_key}", headers=auth_headers,
                              allow_redirects=False)
        assert resolve.status_code == 400, \
            f"Expected 400 for URL not yet active, got {resolve.status_code}: {resolve.text}"

    def test_url_without_window_resolves_immediately(
        self, request, base_url, auth_headers
    ):
        shorten = req_lib.post(
            f"{base_url}/short-url", headers=auth_headers,
            json={"url": "https://example.com/lc001-permanent"},
        )
        assert shorten.status_code == 201
        short_key = shorten.json().get("shortUrl", "").rstrip("/").split("/")[-1]

        resolve = _get(request.node, f"{base_url}/{short_key}", auth_headers)
        assert resolve.status_code in (200, 301, 302, 307), \
            f"Expected redirect/200 for permanent URL, got {resolve.status_code}: {resolve.text}"


# ---------------------------------------------------------------------------
# BR-LC-002: Expired URL remains in database unreachable
# ---------------------------------------------------------------------------

class TestBR_LC_002_expired_url_remains_in_database_unreachable:
    """An expired URL returns 400 on redirect; it is not automatically cleaned up."""

    def test_expired_url_returns_400_on_redirect(self, request, base_url, auth_headers):
        valid_till = _now_ms() + 2_000  # expires in 2 seconds

        shorten = req_lib.post(
            f"{base_url}/short-url", headers=auth_headers,
            json={"url": "https://example.com/lc002-expiry", "validTill": valid_till},
        )
        assert shorten.status_code == 201, f"Shorten failed: {shorten.text}"
        short_key = shorten.json().get("shortUrl", "").rstrip("/").split("/")[-1]

        time.sleep(3)  # wait for URL to expire

        resolve = _get(request.node, f"{base_url}/{short_key}", auth_headers)
        assert resolve.status_code == 400, \
            f"Expected 400 for expired URL, got {resolve.status_code}: {resolve.text}"


# ---------------------------------------------------------------------------
# BR-LC-003: Config deletion is hard and immediate
# ---------------------------------------------------------------------------

class TestBR_LC_003_config_deletion_is_hard_and_immediate:
    """Deleting config permanently removes it; subsequent GET returns 404."""

    def test_get_after_delete_returns_404(self, request, base_url, auth_headers):
        existing = req_lib.get(f"{base_url}/config", headers=auth_headers)
        assert existing.status_code == 200, "Precondition: config must exist"
        saved = existing.json()

        req_lib.delete(f"{base_url}/config", headers=auth_headers)
        try:
            get_resp = _get(request.node, f"{base_url}/config", auth_headers)
            assert get_resp.status_code == 404, \
                f"Expected 404 after config deletion, got {get_resp.status_code}: {get_resp.text}"
        finally:
            req_lib.post(f"{base_url}/config", headers=auth_headers, json={
                "shortKeyLength":     saved.get("shortKeyLength", 6),
                "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
            })


# ---------------------------------------------------------------------------
# BR-LC-004: Only permanent URLs are cached
# ---------------------------------------------------------------------------

class TestBR_LC_004_only_permanent_urls_are_cached:
    """URLs with validTill set are NOT cached; permanent URLs (no expiry) are cached."""

    def test_permanent_url_can_be_resolved_multiple_times(
        self, request, base_url, auth_headers
    ):
        shorten = req_lib.post(
            f"{base_url}/short-url", headers=auth_headers,
            json={"url": "https://example.com/lc004-permanent"},
        )
        assert shorten.status_code == 201
        short_key = shorten.json().get("shortUrl", "").rstrip("/").split("/")[-1]

        for _ in range(2):
            resolve = req_lib.get(f"{base_url}/{short_key}", headers=auth_headers,
                                  allow_redirects=False)
            assert resolve.status_code in (200, 301, 302, 307), \
                f"Permanent URL should resolve on repeat requests: {resolve.status_code}"
