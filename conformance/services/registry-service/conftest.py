import os
import pytest
import schemathesis.openapi
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

_test_outcomes = []


def pytest_addoption(parser):
    parser.addoption("--base-url", action="store", required=True)
    parser.addoption("--api-token", action="store", default="")
    parser.addoption("--tenant-id", action="store", default="")
    parser.addoption("--gateway", action="store", default=None,
                     choices=["kong", "aws", "custom"])


@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    token = request.config.getoption("--api-token")
    tenant = request.config.getoption("--tenant-id")
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if tenant:
        headers["X-Tenant-ID"] = tenant
    return headers


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema(base_url):
    schema_path = os.path.join(os.path.dirname(__file__), "schema.yaml")
    return schemathesis.openapi.from_path(schema_path)


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
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


def pytest_runtest_logreport(report):
    if report.when != "call":
        return
    _test_outcomes.append({
        "name": report.nodeid,
        "outcome": "PASSED" if report.passed else ("FAILED" if report.failed else "SKIPPED"),
    })


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    if not _test_outcomes:
        return
    terminalreporter.write_sep("=", "Conformance test summary")
    for t in _test_outcomes:
        color = "green" if t["outcome"] == "PASSED" else ("red" if t["outcome"] == "FAILED" else "yellow")
        terminalreporter.write_line(f"  [{t['outcome']}] {t['name']}", **{color: True})
