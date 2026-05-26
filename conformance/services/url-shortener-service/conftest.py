import os
import pytest
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


def pytest_addoption(parser):
    parser.addoption("--base-url", action="store", required=True,
                     help="Base URL of the service under test")
    parser.addoption("--api-token", action="store", default="",
                     help="Bearer token for authenticated endpoints")
    parser.addoption("--gateway", action="store", default=None,
                     choices=["kong", "aws", "custom"],
                     help="Gateway profile for header validation.")


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
    schema_path = os.path.join(os.path.dirname(__file__), "schema.yaml")
    return schemathesis.openapi.from_path(schema_path, base_url=base_url)


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
