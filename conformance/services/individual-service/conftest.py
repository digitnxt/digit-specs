import pathlib
import threading
import pytest
import requests as _requests
import schemathesis
from tests.helpers.curl_builder import build_curl

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

_summary_records = []
_tl = threading.local()
_original = _requests.Session.send


def pytest_addoption(parser):
    parser.addoption("--base-url", action="store", required=True,
                     help="Base URL of the service under test (e.g. http://localhost:8080)")
    parser.addoption("--api-token", action="store", default="",
                     help="Bearer token for authenticated endpoints")
    parser.addoption("--gateway", action="store", default=None,
                     choices=["kong", "aws", "custom"],
                     help="Gateway profile for header validation.")
    parser.addoption("--tenant-id", action="store", default="default",
                     help="X-Tenant-ID header value")
    parser.addoption("--user-id", action="store", default="conformance-test-user",
                     help="X-User-ID header value (required by Individual Service middleware)")


@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    """
    Headers sent on every authenticated request. The Individual Service
    middleware requires X-User-ID on top of the usual bearer token + tenant.
    """
    token = request.config.getoption("--api-token")
    tenant = request.config.getoption("--tenant-id")
    user_id = request.config.getoption("--user-id")
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if tenant:
        headers["X-Tenant-ID"] = tenant
    if user_id:
        headers["X-User-ID"] = user_id
    return headers


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema(base_url):
    _SCHEMA_PATH = pathlib.Path(__file__).parent / "individual.yaml"
    return schemathesis.openapi.from_path(_SCHEMA_PATH)


# ── HTTP capture (terminal failed-only summary) ───────────────────────────────

@pytest.fixture(autouse=True)
def _http_capture():
    _tl.log = []

    def _patched(self, prepared, **kwargs):
        response = _original(self, prepared, **kwargs)
        body = ""
        if prepared.body:
            try:
                raw = prepared.body.decode("utf-8") if isinstance(prepared.body, bytes) else str(prepared.body)
                body = raw[:300] + ("…" if len(raw) > 300 else "")
            except Exception:
                body = "<binary>"
        _tl.log.append({
            "method": prepared.method,
            "url": prepared.url,
            "body": body,
            "status_code": response.status_code,
        })
        return response

    _requests.Session.send = _patched
    yield
    _requests.Session.send = _original


# ── cURL injection into pytest-html report ────────────────────────────────────

@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Attach cURL command to the HTML report for every failed test."""
    outcome = yield
    report = outcome.get_result()

    if report.when == "call" and report.failed:
        prepared_req = getattr(item, "_curl_request", None)
        if prepared_req is not None:
            try:
                from pytest_html import extras as html_extras
                curl_cmd = build_curl(prepared_req)
                report.extras = getattr(report, "extras", [])
                report.extras.append(
                    html_extras.html(
                        '<div style="margin-top:8px">'
                        '<strong>Replay with cURL</strong>'
                        '<pre style="background:#1e1e1e;color:#d4d4d4;padding:12px;'
                        'border-radius:4px;overflow-x:auto;font-size:12px;margin-top:4px">'
                        f'{curl_cmd}'
                        '</pre></div>'
                    )
                )
            except Exception:
                pass  # pytest-html not installed — skip silently


# ── Failed-only terminal summary ──────────────────────────────────────────────

def pytest_runtest_logreport(report):
    if report.when != "call":
        return
    if not report.failed:
        return
    logs = getattr(_tl, "log", [])
    error_msg = ""
    if report.longrepr:
        lines = str(report.longrepr).splitlines()
        error_msg = next((l for l in reversed(lines) if l.strip()), "")[:120]
    for entry in logs:
        _summary_records.append({
            "method":  entry["method"],
            "status":  entry["status_code"],
            "url":     entry["url"],
            "body":    entry["body"],
            "test":    report.nodeid,
            "error":   error_msg,
        })


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    terminalreporter.write_sep("=", "Failed Request Summary")

    if not _summary_records:
        terminalreporter.write_line("  \u2713 No failures \u2014 all requests passed.")
        return

    col_w = {"method": 7, "status": 6, "url": 52, "body": 40, "test": 48, "error": 50}
    header = (
        f"  {'METHOD':<{col_w['method']}} {'STATUS':<{col_w['status']}} "
        f"{'URL':<{col_w['url']}} {'REQUEST BODY':<{col_w['body']}} "
        f"{'TEST':<{col_w['test']}} {'ERROR':<{col_w['error']}}"
    )
    terminalreporter.write_line(header)
    terminalreporter.write_line("  " + "-" * (sum(col_w.values()) + len(col_w) + 4))

    red = "\033[31m"
    reset = "\033[0m"

    for r in _summary_records:
        url   = r["url"][-col_w["url"]:]   if len(r["url"])   > col_w["url"]   else r["url"]
        body  = r["body"][:col_w["body"]]  if len(r["body"])  > col_w["body"]  else r["body"]
        test  = r["test"][-col_w["test"]:] if len(r["test"])  > col_w["test"]  else r["test"]
        error = r["error"][:col_w["error"]]
        line = (
            f"  {r['method']:<{col_w['method']}} {str(r['status']):<{col_w['status']}} "
            f"{url:<{col_w['url']}} {body:<{col_w['body']}} "
            f"{test:<{col_w['test']}} {error:<{col_w['error']}}"
        )
        terminalreporter.write_line(f"{red}{line}{reset}")

    terminalreporter.write_line("")
    terminalreporter.write_line(f"  Total failed requests: {len(_summary_records)}")
