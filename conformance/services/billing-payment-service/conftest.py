import pathlib
import threading
import pytest
import schemathesis
from tests.helpers.curl_builder import build_curl

_SCHEMA_PATH = pathlib.Path(__file__).parent / "schema.yaml"

GATEWAY_HEADER_PROFILES = {
    "kong": {
        "X-RateLimit-Limit-Minute":        {"required": True,  "type": int},
        "X-RateLimit-Remaining-Minute":    {"required": True,  "type": int},
        "X-Kong-Request-Id":               {"required": True,  "type": str},
        "X-Kong-Upstream-Latency":         {"required": False, "type": int},
        "X-Kong-Proxy-Latency":            {"required": False, "type": int},
    },
    "aws": {
        "x-amzn-RequestId":                {"required": True,  "type": str},
        "x-amzn-Remapped-Content-Length":  {"required": False, "type": int},
        "x-amz-apigw-id":                  {"required": True,  "type": str},
        "X-Cache":                         {"required": False, "type": str},
    },
    "custom": {},
}

# ── Summary state ─────────────────────────────────────────────────────────────

_summary_records = []   # only FAILED/ERROR rows land here
_tl = threading.local()


def pytest_addoption(parser):
    parser.addoption(
        "--base-url",
        action="store",
        required=True,
        help="Base URL of the billing-payment service (e.g. http://localhost:8080/billing/v3)"
    )
    parser.addoption(
        "--api-token",
        action="store",
        default="",
        help="Bearer token for authenticated endpoints"
    )
    parser.addoption(
        "--gateway",
        action="store",
        default=None,
        choices=["kong", "aws", "custom"],
        help="Gateway profile for header validation"
    )
    parser.addoption(
        "--tenant-id",
        action="store",
        default="default",
        help="X-Tenant-ID header value for all requests"
    )


# ── HTTP capture (intercepts every requests.Session.send) ─────────────────────

@pytest.fixture(autouse=True)
def _http_capture(request):
    import requests as _req

    _tl.log    = []
    _tl.nodeid = request.node.nodeid
    _original  = _req.Session.send

    def _patched(self, prepared, **kwargs):
        response = _original(self, prepared, **kwargs)
        # capture request body (truncated for readability)
        body = ""
        if prepared.body:
            try:
                raw = prepared.body.decode("utf-8") if isinstance(prepared.body, bytes) else str(prepared.body)
                body = raw[:300] + ("…" if len(raw) > 300 else "")
            except Exception:
                body = "<binary>"
        _tl.log.append({
            "method":      prepared.method,
            "url":         prepared.url,
            "body":        body,
            "status_code": response.status_code,
        })
        return response

    _req.Session.send = _patched
    yield
    _req.Session.send = _original


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
                pass


# ── Collect FAILED tests only ──────────────────────────────────────────────────

def pytest_runtest_logreport(report):
    if report.when != "call":
        return
    if not report.failed:
        return                          # only track failures

    nodeid   = getattr(_tl, "nodeid", report.nodeid)
    http_log = list(getattr(_tl, "log", []))

    longrepr = report.longrepr
    if hasattr(longrepr, "reprcrash"):
        error_msg = longrepr.reprcrash.message
    else:
        error_msg = str(longrepr).split("\n")[0]

    if http_log:
        for entry in http_log:
            _summary_records.append({
                "test":        nodeid,
                "method":      entry["method"],
                "url":         entry["url"],
                "body":        entry["body"],
                "status_code": str(entry["status_code"]),
                "error":       error_msg,
            })
    else:
        _summary_records.append({
            "test":        nodeid,
            "method":      "-",
            "url":         "-",
            "body":        "-",
            "status_code": "-",
            "error":       error_msg,
        })


# ── Failed-request summary table ──────────────────────────────────────────────

def pytest_terminal_summary(terminalreporter, exitstatus, config):
    if not _summary_records:
        terminalreporter.write_sep("=", "Failed Request Summary")
        terminalreporter.write_line("  ✓ No failures — all requests passed.")
        return

    MAX_URL   = 65
    MAX_BODY  = 60
    MAX_ERROR = 80
    MAX_TEST  = 55

    def _trim(s, n):
        s = str(s)
        return s if len(s) <= n else s[:n - 1] + "…"

    w_method = max(6,  max(len(r["method"])      for r in _summary_records))
    w_status = max(6,  max(len(r["status_code"]) for r in _summary_records))
    w_url    = min(MAX_URL,  max(3, max(len(r["url"])  for r in _summary_records)))
    w_body   = min(MAX_BODY, max(4, max(len(r["body"]) for r in _summary_records)))
    w_test   = min(MAX_TEST, max(4, max(len(r["test"]) for r in _summary_records)))

    header = (
        f"{'METHOD':<{w_method}}  "
        f"{'STATUS':<{w_status}}  "
        f"{'URL':<{w_url}}  "
        f"{'REQUEST BODY':<{w_body}}  "
        f"{'TEST':<{w_test}}  "
        f"ERROR"
    )
    separator = "─" * min(len(header) + MAX_ERROR, 220)

    terminalreporter.write_sep("=", f"Failed Request Summary  ({len(_summary_records)} failed request(s))")
    terminalreporter.write_line(header)
    terminalreporter.write_line(separator)

    for r in _summary_records:
        line = (
            f"{r['method']:<{w_method}}  "
            f"{r['status_code']:<{w_status}}  "
            f"{_trim(r['url'],   w_url):<{w_url}}  "
            f"{_trim(r['body'],  w_body):<{w_body}}  "
            f"{_trim(r['test'],  w_test):<{w_test}}  "
            f"{_trim(r['error'], MAX_ERROR)}"
        )
        terminalreporter.write_line(line, red=True)

    terminalreporter.write_line(separator)
    terminalreporter.write_line(f"  Total failed requests: {len(_summary_records)}")


# ── Session-scoped fixtures ───────────────────────────────────────────────────

@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def tenant_id(request):
    return request.config.getoption("--tenant-id")


@pytest.fixture(scope="session")
def auth_headers(request, tenant_id):
    token = request.config.getoption("--api-token")
    headers = {"X-Tenant-ID": tenant_id}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema():
    return schemathesis.openapi.from_path(_SCHEMA_PATH)
