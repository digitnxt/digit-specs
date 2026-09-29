import os
import pytest
import requests
import schemathesis
from tests.helpers.curl_builder import build_curl

# Hypothesis profiles for the schemathesis module. The default 200ms per-example
# deadline is unusable against a remote gateway; disable it. Select with
# `--hypothesis-profile=bounded` (fast smoke) or `=full` (thorough).
try:
    from hypothesis import HealthCheck, settings

    _SUPPRESS = [HealthCheck.too_slow, HealthCheck.filter_too_much]
    settings.register_profile("bounded", max_examples=25, deadline=None, suppress_health_check=_SUPPRESS)
    settings.register_profile("full", max_examples=100, deadline=None, suppress_health_check=_SUPPRESS)
except Exception:
    pass

# The account route has NO Kong plugin applied (it is the bootstrap service that
# mints tenants/realms, so there is no pre-existing token/tenant to enforce).
# Kong core still stamps X-Kong-Request-Id + latency headers on every proxied
# response, but the rate-limit headers only appear when the plugin is enabled —
# so they are optional here.
GATEWAY_HEADER_PROFILES = {
    "kong": {
        "X-Kong-Request-Id":            {"required": True,  "type": str},
        "X-Kong-Upstream-Latency":      {"required": False, "type": int},
        "X-Kong-Proxy-Latency":         {"required": False, "type": int},
        "X-RateLimit-Limit-Minute":     {"required": False, "type": int},
        "X-RateLimit-Remaining-Minute": {"required": False, "type": int},
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
                     help="Base URL incl. context path, e.g. https://host/accounts/v3")
    parser.addoption("--api-token", action="store", default="",
                     help="Optional bearer token (account route has no auth plugin; usually empty)")
    parser.addoption("--gateway", action="store", default=None,
                     choices=["kong", "aws", "custom"],
                     help="Gateway profile for header validation.")


@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    """Headers for every request.

    The account service does NO in-service auth and Kong applies no auth plugin
    to this route, so we run tokenless by default. A token is sent only if one
    is explicitly provided. Note: the service reads X-Client-Id (not X-User-Id)
    for audit attribution; X-Tenant-Id (a tenant UUID) is required only for the
    /config endpoints and is set per-request by those tests, not globally.
    """
    token = request.config.getoption("--api-token")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema(base_url):
    schema_path = os.path.join(os.path.dirname(__file__), "schema.yaml")
    return schemathesis.openapi.from_path(schema_path, base_url=base_url)


@pytest.fixture
def tenant_factory(base_url, auth_headers):
    """Create tenants and guarantee cleanup.

    createTenant provisions a real tenant + Keycloak realm; deleteTenant is a
    hard cascade that also removes the realm. This fixture creates tenants,
    tracks their ids, and hard-deletes every one at teardown so the shared
    environment is left clean. Also exposes `.track(id)` for tenants created
    by other means, and `.ids` for the list.
    """
    class _Factory:
        def __init__(self):
            self.ids = []

        def create(self, **overrides):
            from tests.helpers.factories import make_tenant_request
            body = make_tenant_request(**overrides)
            r = requests.post(f"{base_url}/tenants", headers=auth_headers, json=body, timeout=30)
            if r.status_code == 201:
                try:
                    self.track(r.json().get("id"))
                except Exception:
                    pass
            return r

        def track(self, tenant_id):
            if tenant_id:
                self.ids.append(tenant_id)

    f = _Factory()
    yield f
    for tid in f.ids:
        try:
            requests.delete(f"{base_url}/tenants/{tid}", headers=auth_headers, timeout=30)
        except Exception:
            pass


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
