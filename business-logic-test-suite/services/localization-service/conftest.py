# services/localization-service/conftest.py
import os
import pytest
from tests.helpers.curl_builder import build_curl

_SERVICE_ROOT    = os.path.dirname(__file__)

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

    }

@pytest.fixture(scope="session", autouse=True)
def provision_seeds(auth_headers, service_urls):
    from tests.helpers.seed import provision
    provision(auth_headers, service_urls)

# ---------------------------------------------------------------------------
# Collect per-test outcomes keyed by rule ID
# ---------------------------------------------------------------------------

_rule_outcomes: dict = {}

def pytest_runtest_logreport(report) -> None:
    """Capture pass/fail/error for every test, grouped by rule ID."""
    if report.when != "call":
        return
    import re as _re
    m = _re.search(r"TestBR_([A-Z]+)_(\d+)_", report.nodeid)
    if not m:
        return
    rule_id = f"BR-{m.group(1)}-{m.group(2)}"
    _rule_outcomes.setdefault(rule_id, []).append(report.outcome)

# ---------------------------------------------------------------------------
# Auto-generate rule coverage table after every test run
# ---------------------------------------------------------------------------

def pytest_sessionfinish(session, exitstatus) -> None:
    """Write test_results.json then generate rule_coverage_table.md."""
    import subprocess, sys, json, os as _os
    results_path = _os.path.join(_SERVICE_ROOT, "reports", "test_results.json")
    _os.makedirs(_os.path.dirname(results_path), exist_ok=True)
    with open(results_path, "w") as _f:
        json.dump(_rule_outcomes, _f)
    subprocess.run(
        [sys.executable, "generate_rule_coverage_table.py"],
        cwd=_SERVICE_ROOT,
    )

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
