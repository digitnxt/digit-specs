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

# ── Summary table state ───────────────────────────────────────────────────────

_summary_records = []
_tl = threading.local()


def pytest_addoption(parser):
    parser.addoption(
        "--base-url",
        action="store",
        required=True,
        help="Base URL of the boundary service (e.g. http://localhost:8080/boundary/v3)"
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
        help="Gateway profile for header validation. Omit if accessing service directly."
    )
    parser.addoption(
        "--tenant-id",
        action="store",
        default="default",
        help="X-Tenant-ID header value for all requests"
    )


# ── HTTP capture fixture ──────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def _http_capture(request):
    import requests as _req
    _tl.log = []
    _tl.nodeid = request.node.nodeid
    _original_send = _req.Session.send

    def _patched_send(self, prepared, **kwargs):
        response = _original_send(self, prepared, **kwargs)
        _tl.log.append({
            "method":      prepared.method,
            "url":         prepared.url,
            "status_code": response.status_code,
        })
        return response

    _req.Session.send = _patched_send
    yield
    _req.Session.send = _original_send


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


# ── Collect results after each test ──────────────────────────────────────────

def pytest_runtest_logreport(report):
    if report.when != "call":
        return

    nodeid  = getattr(_tl, "nodeid", report.nodeid)
    http_log = list(getattr(_tl, "log", []))

    error_msg = ""
    if report.failed:
        longrepr = report.longrepr
        if hasattr(longrepr, "reprcrash"):
            error_msg = longrepr.reprcrash.message
        else:
            error_msg = str(longrepr).split("\n")[0]

    outcome = report.outcome.upper()

    if http_log:
        for entry in http_log:
            _summary_records.append({
                "test":        nodeid,
                "method":      entry["method"],
                "url":         entry["url"],
                "status_code": str(entry["status_code"]),
                "outcome":     outcome,
                "error":       error_msg,
            })
    else:
        _summary_records.append({
            "test":        nodeid,
            "method":      "-",
            "url":         "-",
            "status_code": "-",
            "outcome":     outcome,
            "error":       error_msg,
        })


# ── Print summary table ───────────────────────────────────────────────────────

def pytest_terminal_summary(terminalreporter, exitstatus, config):
    if not _summary_records:
        return

    MAX_URL   = 70
    MAX_ERROR = 80
    MAX_TEST  = 60

    def _trim(s, n):
        s = str(s)
        return s if len(s) <= n else s[: n - 3] + "..."

    w_outcome = max(7,  max(len(r["outcome"])     for r in _summary_records))
    w_method  = max(6,  max(len(r["method"])      for r in _summary_records))
    w_status  = max(6,  max(len(r["status_code"]) for r in _summary_records))
    w_url     = min(MAX_URL,  max(3, max(len(r["url"])  for r in _summary_records)))
    w_test    = min(MAX_TEST, max(4, max(len(r["test"]) for r in _summary_records)))

    header = (
        f"{'OUTCOME':<{w_outcome}}  "
        f"{'METHOD':<{w_method}}  "
        f"{'STATUS':<{w_status}}  "
        f"{'URL':<{w_url}}  "
        f"{'TEST':<{w_test}}  "
        f"ERROR"
    )
    separator = "─" * (len(header) + MAX_ERROR)

    terminalreporter.write_sep("=", "HTTP Request Summary")
    terminalreporter.write_line(header)
    terminalreporter.write_line(separator)

    OUTCOME_COLORS = {"PASSED": "green", "FAILED": "red", "ERROR": "red", "SKIPPED": "yellow"}

    for r in _summary_records:
        color = OUTCOME_COLORS.get(r["outcome"], "white")
        line = (
            f"{r['outcome']:<{w_outcome}}  "
            f"{r['method']:<{w_method}}  "
            f"{r['status_code']:<{w_status}}  "
            f"{_trim(r['url'],   w_url):<{w_url}}  "
            f"{_trim(r['test'],  w_test):<{w_test}}  "
            f"{_trim(r['error'], MAX_ERROR)}"
        )
        terminalreporter.write_line(line, **{color: True})

    terminalreporter.write_line(separator)

    passed  = sum(1 for r in _summary_records if r["outcome"] == "PASSED")
    failed  = sum(1 for r in _summary_records if r["outcome"] in ("FAILED", "ERROR"))
    skipped = sum(1 for r in _summary_records if r["outcome"] == "SKIPPED")
    terminalreporter.write_line(
        f"  Total requests: {len(_summary_records)}  |  "
        f"passed: {passed}  failed: {failed}  skipped: {skipped}"
    )


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
def swagger_schema(base_url):
    return schemathesis.openapi.from_path(_SCHEMA_PATH)
