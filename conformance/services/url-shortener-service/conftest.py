import os
import re
import tempfile
import pytest
import requests as _http
import schemathesis
from tests.helpers.curl_builder import build_curl

_SERVICE_ROOT = os.path.dirname(__file__)
_SCHEMA_ORIGINAL = os.path.join(_SERVICE_ROOT, "schema.yaml")
_SCHEMA_RESOLVED = os.path.join(_SERVICE_ROOT, "schema.resolved.yaml")


def _raw_url_to_api_url(raw_url: str) -> str:
    """
    Convert a raw.githubusercontent.com URL to a GitHub Contents API URL.

    raw.githubusercontent.com returns 404 for private repos regardless of auth.
    The Contents API works correctly with fine-grained PATs.

    Example:
      https://raw.githubusercontent.com/org/repo/refs/heads/main/v3/common.yaml
      → https://api.github.com/repos/org/repo/contents/v3/common.yaml?ref=refs/heads/main
    """
    suffix = raw_url[len("https://raw.githubusercontent.com/"):]
    owner, repo, *rest = suffix.split("/")
    rest_str = "/".join(rest)

    if rest_str.startswith("refs/heads/") or rest_str.startswith("refs/tags/"):
        parts = rest_str.split("/")
        ref      = "/".join(parts[:3])   # refs/heads/main
        filepath = "/".join(parts[3:])
    else:
        parts    = rest_str.split("/", 1)
        ref      = parts[0]
        filepath = parts[1] if len(parts) > 1 else ""

    return f"https://api.github.com/repos/{owner}/{repo}/contents/{filepath}?ref={ref}"


def _resolve_remote_refs(token: str) -> None:
    """
    Download every remote $ref URL in schema.yaml via the GitHub Contents API
    and rewrite them to local temp-file paths, writing the result to
    schema.resolved.yaml.

    Called from pytest_configure (before test collection) so that
    test_schema_conformance.py can load the resolved file at module-import time.

    Uses the GitHub Contents API (not raw.githubusercontent.com) because
    raw.githubusercontent.com returns 404 for private repos even with a valid PAT.
    The Accept: application/vnd.github.raw+json header returns the file content
    directly instead of the base64-encoded JSON wrapper.
    """
    with open(_SCHEMA_ORIGINAL) as f:
        content = f.read()

    raw_urls = re.findall(r"https://raw\.githubusercontent\.com/[^\s'\"#]+", content)
    base_urls = sorted({url.split("#")[0] for url in raw_urls})

    if not base_urls:
        return

    tmpdir = tempfile.mkdtemp(prefix="digit_specs_refs_")
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github.raw+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    for raw_url in base_urls:
        api_url = _raw_url_to_api_url(raw_url)
        try:
            resp = _http.get(api_url, headers=headers, timeout=15)
            resp.raise_for_status()
        except _http.exceptions.RequestException as exc:
            raise RuntimeError(
                f"Failed to fetch schema $ref component.\n"
                f"  Raw URL : {raw_url}\n"
                f"  API URL : {api_url}\n"
                f"  Error   : {exc}\n"
                f"Check that --schema-token / SCHEMA_TOKEN is a valid fine-grained PAT\n"
                f"with Contents: Read-only access to the digit-specs repo."
            ) from exc

        filename = raw_url.rstrip("/").split("/")[-1]
        local_path = os.path.join(tmpdir, filename)
        with open(local_path, "w") as f:
            f.write(resp.text)
        content = content.replace(raw_url, local_path)

    with open(_SCHEMA_RESOLVED, "w") as f:
        f.write(content)


def _active_schema_path() -> str:
    """Return the resolved schema if it exists, otherwise the original."""
    return _SCHEMA_RESOLVED if os.path.exists(_SCHEMA_RESOLVED) else _SCHEMA_ORIGINAL


GATEWAY_HEADER_PROFILES = {
    "kong": {
        "X-RateLimit-Limit-Minute":     {"required": True,  "type": int},
        "X-RateLimit-Remaining-Minute": {"required": True,  "type": int},
        "X-Kong-Request-Id":            {"required": True,  "type": str},
        "X-Kong-Upstream-Latency":      {"required": False, "type": int},
        "X-Kong-Proxy-Latency":         {"required": False, "type": int},
    },
    "aws": {
        "x-amzn-RequestId":               {"required": True,  "type": str},
        "x-amzn-Remapped-Content-Length": {"required": False, "type": int},
        "x-amz-apigw-id":                 {"required": True,  "type": str},
        "X-Cache":                        {"required": False, "type": str},
    },
    "custom": {},
}


def pytest_addoption(parser):
    parser.addoption("--base-url", action="store", required=True,
                     help="Base URL of the service under test")
    parser.addoption("--api-token", action="store", default="",
                     help="Bearer token for authenticated endpoints")
    parser.addoption("--schema-token", action="store",
                     default=os.environ.get("SCHEMA_TOKEN", ""),
                     help="GitHub PAT for resolving private $ref components in schema.yaml. "
                          "Can also be set via the SCHEMA_TOKEN environment variable.")
    parser.addoption("--gateway", action="store", default=None,
                     choices=["kong", "aws", "custom"],
                     help="Gateway profile for header validation.")


def pytest_configure(config):
    """Resolve remote $refs before test collection using the provided schema token."""
    try:
        token = config.getoption("--schema-token")
    except ValueError:
        token = os.environ.get("SCHEMA_TOKEN", "")
    if token:
        _resolve_remote_refs(token)


@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    token = request.config.getoption("--api-token")
    # X-Tenant-ID is injected by the gateway from the bearer token — do not set manually.
    return {"Authorization": f"Bearer {token}"} if token else {}


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema(base_url):
    return schemathesis.openapi.from_path(_active_schema_path(), base_url=base_url)


@pytest.fixture(scope="session")
def valid_short_key(base_url, auth_headers):
    """
    Create a real short URL once per session.
    Used by schema conformance tests for GET /{key} so Schemathesis sends a key
    that actually exists, rather than a random value that always returns 404.
    """
    r = _http.get(f"{base_url}/v3/config", headers=auth_headers)
    if r.status_code == 404:
        _http.post(f"{base_url}/v3/config", headers=auth_headers,
                   json={"shortKeyLength": 4, "maxShortKeyRetries": 10})
    r = _http.post(f"{base_url}/v3/short-url", headers=auth_headers,
                   json={"url": "https://example.com/schema-conformance-probe"})
    assert r.status_code == 201, f"Failed to create short URL for conformance probe: {r.text}"
    return r.json()["shortUrl"].rstrip("/").split("/")[-1]


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    from pytest_html import extras as html_extras

    outcome = yield
    report = outcome.get_result()

    if report.when == "call" and report.failed:
        prepared_req = getattr(item, "_curl_request", None)
        if prepared_req is not None:
            curl_cmd = build_curl(prepared_req)
            report.extras = getattr(report, "extras", [])
            report.extras.append(
                html_extras.html(
                    f'<div style="background:#1e1e1e;color:#d4d4d4;padding:12px;'
                    f'border-radius:4px;margin-top:8px;">'
                    f'<strong style="color:#9cdcfe;">Replay with cURL</strong>'
                    f'<pre style="margin:8px 0 0;white-space:pre-wrap;word-break:break-all;">'
                    f'{curl_cmd}'
                    f'</pre></div>'
                )
            )
