# services/billing-payment-service/conftest.py
import os
import re
import tempfile
import pytest
import requests as _http
from tests.helpers.curl_builder import build_curl

_SERVICE_ROOT    = os.path.dirname(__file__)
_SCHEMA_ORIGINAL = os.path.join(_SERVICE_ROOT, "schema.yaml")
_SCHEMA_RESOLVED = os.path.join(_SERVICE_ROOT, "schema.resolved.yaml")


# ---------------------------------------------------------------------------
# Private $ref resolution via GitHub Contents API
# ---------------------------------------------------------------------------

def _raw_url_to_api_url(raw_url: str) -> str:
    suffix = raw_url[len("https://raw.githubusercontent.com/"):]
    owner, repo, *rest = suffix.split("/")
    rest_str = "/".join(rest)
    if rest_str.startswith("refs/heads/") or rest_str.startswith("refs/tags/"):
        parts    = rest_str.split("/")
        ref      = "/".join(parts[:3])
        filepath = "/".join(parts[3:])
    else:
        parts    = rest_str.split("/", 1)
        ref      = parts[0]
        filepath = parts[1] if len(parts) > 1 else ""
    return f"https://api.github.com/repos/{owner}/{repo}/contents/{filepath}?ref={ref}"


def _resolve_remote_refs(token: str) -> None:
    with open(_SCHEMA_ORIGINAL) as f:
        content = f.read()
    raw_urls  = re.findall(r"https://raw\.githubusercontent\.com/[^\s'\"#]+", content)
    base_urls = sorted({url.split("#")[0] for url in raw_urls})
    if not base_urls:
        return
    tmpdir  = tempfile.mkdtemp(prefix="svc_spec_refs_")
    headers = {
        "Authorization":        f"Bearer {token}",
        "Accept":               "application/vnd.github.raw+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    for raw_url in base_urls:
        api_url = _raw_url_to_api_url(raw_url)
        try:
            resp = _http.get(api_url, headers=headers, timeout=15)
            resp.raise_for_status()
        except _http.exceptions.RequestException as exc:
            raise RuntimeError(
                f"Failed to fetch schema $ref.\n"
                f"  Raw URL : {raw_url}\n"
                f"  API URL : {api_url}\n"
                f"  Error   : {exc}"
            ) from exc
        filename   = raw_url.rstrip("/").split("/")[-1]
        local_path = os.path.join(tmpdir, filename)
        with open(local_path, "w") as f:
            f.write(resp.text)
        content = content.replace(raw_url, local_path)
    with open(_SCHEMA_RESOLVED, "w") as f:
        f.write(content)


# ---------------------------------------------------------------------------
# pytest hooks
# ---------------------------------------------------------------------------

def pytest_addoption(parser):
    parser.addoption("--base-url",     action="store", required=True,
                     help="Base URL of the billing-payment service under test")
    parser.addoption("--api-token",    action="store", default="",
                     help="Bearer token; encodes tenant identity via gateway")
    parser.addoption("--schema-token", action="store",
                     default=os.environ.get("SCHEMA_TOKEN", ""),
                     help="GitHub PAT for private $ref resolution")
    parser.addoption("--idgen-url",    action="store", default=None,
                     help="Base URL of the IDGen service (for cross-module bill/receipt number tests)")


def pytest_configure(config):
    try:
        token = config.getoption("--schema-token")
    except ValueError:
        token = os.environ.get("SCHEMA_TOKEN", "")
    if token:
        _resolve_remote_refs(token)


# ---------------------------------------------------------------------------
# Session-scoped fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    token = request.config.getoption("--api-token")
    return {"Authorization": f"Bearer {token}"} if token else {}


@pytest.fixture(scope="session")
def service_urls(request):
    def _get(arg):
        try:
            val = request.config.getoption(arg) or ""
        except ValueError:
            val = ""
        return val.rstrip("/") or None

    return {
        "--base-url": request.config.getoption("--base-url").rstrip("/"),
        "--idgen-url":   _get("--idgen-url"),
    }


@pytest.fixture(scope="session", autouse=True)
def provision_seeds(auth_headers, service_urls):
    from tests.helpers.seed import provision
    provision(auth_headers, service_urls)


# ---------------------------------------------------------------------------
# cURL injection into pytest-html report
# ---------------------------------------------------------------------------

@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    from pytest_html import extras as html_extras
    outcome = yield
    report  = outcome.get_result()
    if report.when == "call" and report.failed:
        prepared_req = getattr(item, "_curl_request", None)
        if prepared_req is not None:
            try:
                curl_cmd = build_curl(prepared_req)
                report.extras = getattr(report, "extras", [])
                report.extras.append(
                    html_extras.html(
                        '<div style="background:#1e1e1e;color:#d4d4d4;padding:12px;'
                        'border-radius:4px;margin-top:8px;">'
                        '<strong style="color:#9cdcfe;">Replay with cURL</strong>'
                        '<pre style="margin:8px 0 0;white-space:pre-wrap;word-break:break-all;">'
                        f'{curl_cmd}'
                        '</pre></div>'
                    )
                )
            except Exception:
                pass
