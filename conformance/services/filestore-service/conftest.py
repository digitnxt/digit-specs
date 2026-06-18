import os
import requests as _req_lib
import pytest
import schemathesis
from tests.helpers.curl_builder import build_curl, build_multipart_curl

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
    parser.addoption("--tenant-id", action="store", default="pb.amritsar",
                     help="Tenant ID for multi-tenant requests")
    parser.addoption("--user-id", action="store", default="conformance-user",
                     help="User ID sent as X-User-Id header (audit enrichment)")
    parser.addoption("--gateway", action="store", default=None,
                     choices=["kong", "aws", "custom"],
                     help="Gateway profile for header validation.")


@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    token = request.config.getoption("--api-token")
    user_id = request.config.getoption("--user-id")
    tenant_id = request.config.getoption("--tenant-id")
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if user_id:
        headers["X-User-Id"] = user_id
    if tenant_id:
        headers["X-Tenant-Id"] = tenant_id
    return headers


@pytest.fixture(scope="session")
def user_id(request):
    return request.config.getoption("--user-id")


@pytest.fixture(scope="session")
def tenant_id(request):
    return request.config.getoption("--tenant-id")


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema(base_url):
    schema_path = os.path.join(os.path.dirname(__file__), "schema.yaml")
    return schemathesis.openapi.from_path(schema_path, base_url=base_url)


@pytest.fixture(scope="session", autouse=True)
def ensure_conformance_doc_categories(base_url, auth_headers):
    """Create doc categories required by conformance tests (idempotent — ignores conflicts)."""
    for module in ("conformance-test", "conformance"):
        _req_lib.post(
            f"{base_url}/document-categories",
            headers=auth_headers,
            json={
                "type": module,
                "code": module,
                "allowedFormats": [],
                "isSensitive": False,
                "isActive": True,
            },
            timeout=10,
        )


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    from pytest_html import extras as html_extras

    outcome = yield
    report = outcome.get_result()

    if report.when == "call" and report.failed:
        curl_cmd = None
        multipart_info = getattr(item, "_curl_multipart", None)
        prepared_req = getattr(item, "_curl_request", None)

        if multipart_info is not None:
            url, headers, fields, files = multipart_info
            curl_cmd = build_multipart_curl(url, headers, fields=fields, files=files)
        elif prepared_req is not None:
            curl_cmd = build_curl(prepared_req)

        if curl_cmd:
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
