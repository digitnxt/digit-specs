"""
Cross-schema rule tests for URL Shortener service.
"""
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


def _delete(node, url, headers):
    r = req_lib.Request("DELETE", url, headers=headers)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CS-001: Config must exist before shortening
# ---------------------------------------------------------------------------

class TestBR_CS_001_config_must_exist_before_shortening:
    """Shortening fails with 404 when no UrlConfig exists for the tenant."""

    def test_shortening_succeeds_when_config_exists(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/short-url", auth_headers, {
            "url": "https://example.com/cs001-happy",
        })
        assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"

    def test_shortening_fails_when_config_absent(self, request, base_url, auth_headers):
        existing = _get(request.node, f"{base_url}/config", auth_headers)
        assert existing.status_code == 200, "Precondition: config must exist to run this test"
        saved = existing.json()

        _delete(request.node, f"{base_url}/config", auth_headers)
        try:
            resp = _post(request.node, f"{base_url}/short-url", auth_headers, {
                "url": "https://example.com/cs001-neg",
            })
            assert resp.status_code == 404, \
                f"Expected 404 when config absent, got {resp.status_code}: {resp.text}"
        finally:
            req_lib.post(f"{base_url}/config", headers=auth_headers, json={
                "shortKeyLength":     saved.get("shortKeyLength", 6),
                "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
            })


# ---------------------------------------------------------------------------
# BR-CS-002: Unique config per tenant enforced
# ---------------------------------------------------------------------------

class TestBR_CS_002_unique_config_per_tenant_enforced:
    """POST /config returns 409 when a config already exists for the tenant."""

    def test_post_config_when_already_exists_returns_409(
        self, request, base_url, auth_headers
    ):
        existing = _get(request.node, f"{base_url}/config", auth_headers)
        assert existing.status_code == 200, "Precondition: config must exist for this test"
        saved = existing.json()

        resp = _post(request.node, f"{base_url}/config", auth_headers, {
            "shortKeyLength":     saved.get("shortKeyLength", 6),
            "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
        })
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate config, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: Config deletion does not cascade to URLs
# ---------------------------------------------------------------------------

class TestBR_CS_004_config_deletion_does_not_cascade_to_urls:
    """Existing short URLs remain resolvable after config is deleted."""

    def test_existing_urls_resolve_after_config_deleted(
        self, request, base_url, auth_headers
    ):
        shorten = _post(request.node, f"{base_url}/short-url", auth_headers, {
            "url": "https://example.com/cs004-persist",
        })
        assert shorten.status_code == 201, f"Shorten failed: {shorten.text}"
        short_key = shorten.json().get("shortUrl", "").rstrip("/").split("/")[-1]

        existing = _get(request.node, f"{base_url}/config", auth_headers)
        assert existing.status_code == 200
        saved = existing.json()

        _delete(request.node, f"{base_url}/config", auth_headers)
        try:
            resolve = req_lib.get(f"{base_url}/{short_key}", headers=auth_headers,
                                  allow_redirects=False)
            assert resolve.status_code in (200, 301, 302, 307), \
                f"Short URL should still resolve after config deletion, got {resolve.status_code}: {resolve.text}"
        finally:
            req_lib.post(f"{base_url}/config", headers=auth_headers, json={
                "shortKeyLength":     saved.get("shortKeyLength", 6),
                "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
            })


# ---------------------------------------------------------------------------
# BR-CS-003: Tenant-scoped short key uniqueness
# ---------------------------------------------------------------------------

class TestBR_CS_003_tenant_scoped_short_key_uniqueness:
    """
    The url_shortener table has a composite unique index on (tenant_id, short_key).
    The service retries up to maxShortKeyRetries times; if all retries collide → 500.
    Observable indirectly: two different long URLs always get distinct short keys.
    """

    def test_two_urls_get_distinct_short_keys(self, request, base_url, auth_headers):
        r1 = _post(request.node, f"{base_url}/short-url", auth_headers,
                   {"url": "https://example.com/cs003-first"})
        r2 = _post(request.node, f"{base_url}/short-url", auth_headers,
                   {"url": "https://example.com/cs003-second"})
        assert r1.status_code == 201, f"First shorten failed: {r1.text}"
        assert r2.status_code == 201, f"Second shorten failed: {r2.text}"
        key1 = r1.json().get("shortUrl", "").rstrip("/").split("/")[-1]
        key2 = r2.json().get("shortUrl", "").rstrip("/").split("/")[-1]
        assert key1 != key2, \
            f"Two different URLs must receive distinct short keys, got '{key1}' and '{key2}'"

    def test_shortening_same_url_twice_yields_different_keys(self, request, base_url, auth_headers):
        r1 = _post(request.node, f"{base_url}/short-url", auth_headers,
                   {"url": "https://example.com/cs003-same"})
        r2 = _post(request.node, f"{base_url}/short-url", auth_headers,
                   {"url": "https://example.com/cs003-same"})
        assert r1.status_code == 201 and r2.status_code == 201, \
            "Same URL shortened twice must both succeed"
        key1 = r1.json().get("shortUrl", "").rstrip("/").split("/")[-1]
        key2 = r2.json().get("shortUrl", "").rstrip("/").split("/")[-1]
        assert key1 != key2, \
            "Each shorten call must produce a unique short key even for the same long URL"


    def test_new_shortening_fails_after_config_deleted(
        self, request, base_url, auth_headers
    ):
        existing = _get(request.node, f"{base_url}/config", auth_headers)
        assert existing.status_code == 200
        saved = existing.json()

        _delete(request.node, f"{base_url}/config", auth_headers)
        try:
            resp = _post(request.node, f"{base_url}/short-url", auth_headers, {
                "url": "https://example.com/cs004-newshorten",
            })
            assert resp.status_code == 404, \
                f"Expected 404 for new shorten after config deletion, got {resp.status_code}: {resp.text}"
        finally:
            req_lib.post(f"{base_url}/config", headers=auth_headers, json={
                "shortKeyLength":     saved.get("shortKeyLength", 6),
                "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
            })
