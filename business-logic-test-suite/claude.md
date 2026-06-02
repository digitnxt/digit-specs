# Claude instructions: generate business logic test suite

## Objective

Generate a **business logic test suite** that validates whether a microservice
correctly enforces its documented rules — cross-field constraints, cross-schema
constraints, lifecycle rules, and cross-module rules.

This is **not** a conformance test. Do not re-test response shapes or status
codes that the conformance suite already covers. Every test must be traceable
to a named rule.

Each microservice gets its **own isolated test suite** — independent folder,
independent execution, independent CI job. No shared test files across services.

---

## Inputs

| # | File | Content | Required |
|---|---|---|---|
| 1 | `schema.yaml` | OpenAPI 3.0 spec — may include `x-business-rules` extensions on operations; these are the primary test targets | Yes |
| 2 | `BUSINESS_RULES.md` | Extracted from the service CLAUDE.md — **these sections only**: Business Logic, Cross-Field Rules, Cross-Schema Rules, Cross-Module Rules, Error Reference. Ignore everything else (DB schema details, config vars, observability, Kafka topics, migration behaviour). | Yes |
| 3 | `env_map.yaml` | Runtime values for `${VAR_NAME}` tokens that appear in rule descriptions (e.g. `IDGEN_BILL_NUMBER_TEMPLATE_CODE: "BILL-NUMBER"`) | When rules reference `${VAR}` tokens |
| 4 | `seed_manifest.yaml` | Entities that must exist before any test runs; each entry declares which service owns it and which CLI arg provides that service's URL | When prerequisite state is required |

---

## Critical constraint — gateway owns tenant identity

The gateway extracts the tenant from `--api-token` and injects it into every
upstream request. Any `X-Tenant-ID` header supplied by the test is silently
overridden.

Consequences:
- **All tests run as a single tenant** — the one encoded in the token
- **No ephemeral tenant fixtures** — you cannot create isolated tenants
- **No tenant-switching** in tests
- **Destructive negative tests**: to test "entity A must exist before B", you
  must temporarily delete A, assert the failure, then restore A in `try/finally`

---

## CLI arguments

| Argument | Required | Description |
|---|---|---|
| `--base-url` | **Yes** | Base URL of the service under test |
| `--api-token` | No | Bearer token; encodes tenant identity via gateway |
| `--schema-token` | No | GitHub fine-grained PAT for resolving private `$ref` in `schema.yaml`. Also readable from `SCHEMA_TOKEN` env var. |
| `--<dep>-url` | No | One per dependency service named in `seed_manifest.yaml` (e.g. `--idgen-url`, `--billing-url`). Cross-module tests and cross-service seeds are **skipped** when the relevant URL is absent. |

`--base-url` absent → immediate failure. All other args optional.

---

## Tech stack

```
pip install pytest requests jsonschema pytest-html pyyaml
```

| Tool | Role |
|---|---|
| Python 3.11+ | Primary language |
| pytest | Runner and reporting |
| requests | HTTP client |
| jsonschema | Response schema validation |
| pytest-html | HTML report with embedded cURL on failure |
| pyyaml | Seed manifest and env map parsing |

No Schemathesis — all tests in this suite are handwritten scenarios.

---

## Output structure

```
services/
└── <svc-name>/
    ├── schema.yaml                          ← immutable spec — never modified
    ├── schema.resolved.yaml                 ← gitignored build artifact
    ├── BUSINESS_RULES.md                    ← input; not generated
    ├── env_map.yaml                         ← input; not generated
    ├── seed_manifest.yaml                   ← input; not generated
    ├── conftest.py
    ├── pytest.ini
    ├── generate_rule_coverage_table.py
    ├── .gitignore
    ├── tests/
    │   ├── __init__.py
    │   ├── test_cross_field_rules.py
    │   ├── test_cross_schema_rules.py
    │   ├── test_lifecycle_rules.py
    │   └── test_cross_module_rules.py       ← opt-in; skipped if dep URL absent
    └── helpers/
        ├── __init__.py
        ├── curl_builder.py
        ├── validators.py
        └── seed.py
    └── reports/
        ├── business_logic.html
        └── rule_coverage_table.md
```

`reports/` and `schema.resolved.yaml` are gitignored. Never commit generated
artifacts.

### `.gitignore`

```
__pycache__/
*.pyc
*.pyo
.pytest_cache/
reports/
schema.resolved.yaml
venv/
.DS_Store
```

---

## `conftest.py` — full implementation

```python
# services/<svc-name>/conftest.py
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
                     help="Base URL of the service under test")
    parser.addoption("--api-token",    action="store", default="",
                     help="Bearer token; encodes tenant identity via gateway")
    parser.addoption("--schema-token", action="store",
                     default=os.environ.get("SCHEMA_TOKEN", ""),
                     help="GitHub PAT for private $ref resolution")
    # Add one --<dep>-url per dependency service referenced in seed_manifest.yaml
    # and test_cross_module_rules.py. Examples:
    parser.addoption("--idgen-url",    action="store", default=None)
    parser.addoption("--billing-url",  action="store", default=None)
    parser.addoption("--notif-url",    action="store", default=None)


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
    """
    Map of CLI arg name → resolved URL.
    Used by seed.py and test_cross_module_rules.py to reach dependency services.
    Value is None when the arg was not provided.
    """
    def _get(arg):
        val = request.config.getoption(arg) or ""
        return val.rstrip("/") or None

    return {
        "--base-url":    request.config.getoption("--base-url").rstrip("/"),
        "--idgen-url":   _get("--idgen-url"),
        "--billing-url": _get("--billing-url"),
        "--notif-url":   _get("--notif-url"),
    }


@pytest.fixture(scope="session", autouse=True)
def provision_seeds(auth_headers, service_urls):
    """
    Ensure all seed prerequisites exist before any test runs.
    Runs once per session. Seeds are never deleted here.
    """
    from tests.helpers.seed import provision
    provision(auth_headers, service_urls)


# ---------------------------------------------------------------------------
# cURL injection into pytest-html report
# ---------------------------------------------------------------------------

@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Attach cURL command to the HTML report for every failed test."""
    from pytest_html import extras as html_extras
    outcome = yield
    report  = outcome.get_result()
    if report.when == "call" and report.failed:
        prepared_req = getattr(item, "_curl_request", None)
        if prepared_req is not None:
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
```

---

## `helpers/curl_builder.py`

```python
import json
import shlex
from typing import Union
import requests

_SKIP_HEADERS = {
    "content-length", "transfer-encoding", "connection",
    "accept-encoding", "user-agent",
}


def build_curl(req: Union[requests.PreparedRequest, requests.Request, dict],
               *, indent: bool = True) -> str:
    if isinstance(req, requests.Request):
        req = req.prepare()
    if isinstance(req, requests.PreparedRequest):
        method  = (req.method or "GET").upper()
        url     = req.url or ""
        headers = dict(req.headers or {})
        body    = req.body
    elif isinstance(req, dict):
        method  = req.get("method", "GET").upper()
        url     = req.get("url", "")
        headers = req.get("headers", {})
        body    = req.get("body")
    else:
        raise TypeError(f"Unsupported type: {type(req)}")

    sep   = " \\\n  " if indent else " "
    parts = [f"curl -X {method}", f"{sep}{shlex.quote(url)}"]
    for key, value in headers.items():
        if key.lower() in _SKIP_HEADERS:
            continue
        parts.append(f"{sep}-H {shlex.quote(f'{key}: {value}')}")
    if body:
        if isinstance(body, bytes):
            try:
                body = body.decode("utf-8")
            except UnicodeDecodeError:
                body = body.hex()
        ct = headers.get("Content-Type", headers.get("content-type", ""))
        if "application/json" in ct:
            try:
                body = json.dumps(json.loads(body), indent=2)
            except (json.JSONDecodeError, TypeError):
                pass
        parts.append(f"{sep}--data-raw {shlex.quote(body)}")
    return "".join(parts)


def attach_curl(node, req) -> None:
    """
    Store a PreparedRequest on the pytest node so the conftest hook renders
    it as cURL in the HTML report on failure.
    Always call BEFORE session.send().
    """
    node._curl_request = req
```

---

## `helpers/seed.py`

```python
"""
Reads seed_manifest.yaml. For each prerequisite:
  1. Issues the CHECK request. If it returns expect_status, entity exists — skip.
  2. Otherwise issues the CREATE request.
  3. 200, 201, or 409 on CREATE = success (409 means already exists).
  4. Any other status raises RuntimeError — the suite cannot safely proceed.

Seeds are never deleted. They represent long-lived platform state.

${VAR_NAME} tokens in values are resolved from env_map.yaml, then OS env.
When base_url_arg for a seed is not in service_urls (URL not provided),
the seed is skipped with a warning — the dependent tests will likely fail.
"""
import os
import yaml
import requests

_SERVICE_ROOT = os.path.join(os.path.dirname(__file__), "../..")
_MANIFEST     = os.path.join(_SERVICE_ROOT, "seed_manifest.yaml")
_ENV_MAP      = os.path.join(_SERVICE_ROOT, "env_map.yaml")


def _load_env_map() -> dict:
    try:
        with open(_ENV_MAP) as f:
            return yaml.safe_load(f) or {}
    except FileNotFoundError:
        return {}


def _resolve(value, env_map):
    if isinstance(value, str) and value.startswith("${") and value.endswith("}"):
        key = value[2:-1]
        return env_map.get(key, os.environ.get(key, value))
    return value


def _resolve_deep(obj, env_map):
    if isinstance(obj, dict):
        return {k: _resolve_deep(v, env_map) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_resolve_deep(i, env_map) for i in obj]
    return _resolve(obj, env_map)


def provision(headers: dict, service_urls: dict) -> None:
    try:
        with open(_MANIFEST) as f:
            manifest = yaml.safe_load(f)
    except FileNotFoundError:
        return

    env_map = _load_env_map()

    for seed in manifest.get("prerequisites", []):
        seed = _resolve_deep(seed, env_map)

        base_url_arg = seed.get("base_url_arg", "--base-url")
        base_url     = service_urls.get(base_url_arg)

        if not base_url:
            print(
                f"[seed] SKIPPED {seed['id']}: "
                f"{base_url_arg} not provided — "
                f"tests that depend on this seed may fail."
            )
            continue

        check = seed["check"]
        resp  = requests.request(
            check["method"],
            f"{base_url}{check['path']}",
            headers=headers,
            params=check.get("params", {}),
            timeout=10,
        )

        if resp.status_code == check.get("expect_status", 200):
            continue  # Entity already exists

        create      = seed["create"]
        create_resp = requests.request(
            create["method"],
            f"{base_url}{create['path']}",
            headers=headers,
            json=create.get("body"),
            timeout=10,
        )

        if create_resp.status_code not in (200, 201, 409):
            raise RuntimeError(
                f"Seed {seed['id']} failed — cannot run tests without this prerequisite.\n"
                f"  Service  : {seed.get('service', 'self')} ({base_url})\n"
                f"  CHECK    : {check['method']} {check['path']}"
                f" → {resp.status_code}\n"
                f"  CREATE   : {create['method']} {create['path']}"
                f" → {create_resp.status_code}\n"
                f"  Response : {create_resp.text[:400]}"
            )
```

---

## `helpers/validators.py`

```python
import jsonschema


def assert_required_fields(body, fields):
    for field in fields:
        assert field in body, f"Required field '{field}' missing from response"


def assert_field_types(body, type_map):
    for field, expected_type in type_map.items():
        if field in body:
            assert isinstance(body[field], expected_type), (
                f"Field '{field}' expected {expected_type.__name__}, "
                f"got {type(body[field]).__name__}: {body[field]!r}"
            )


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)
```

---

## `pytest.ini`

```ini
[pytest]
testpaths = tests
addopts   = -v -p no:randomly
```

`-p no:randomly` is mandatory. Business logic tests share state on a single
tenant and must run in deterministic, sequential order. Parallel execution is
unsafe.

---

## Test file specifications

### Rule naming convention

Every test class maps to exactly one rule:

```
TestBR_<CATEGORY>_<NNN>_<short_slug>
```

Examples: `TestBR_CF_001_validity_window_ordering`,
`TestBR_CS_001_config_required_before_shortening`,
`TestBR_LC_001_global_scope_start_immutable`,
`TestBR_CM_001_billing_calls_idgen_for_bill_number`

Categories: `CF` = cross-field, `CS` = cross-schema, `LC` = lifecycle,
`CM` = cross-module.

If `x-business-rules` extensions exist in `schema.yaml`, use their `id` field
directly. If not, derive IDs from the section headings in `BUSINESS_RULES.md`
using the pattern above.

---

### `tests/test_cross_field_rules.py`

Rules where two or more fields in the same request body interact.

**Generate four scenarios per rule:**
1. Happy path — fields valid and consistent
2. Violation — fields present but constraint broken
3. Boundary — fields at the exact limit (e.g. `validFrom = validTill - 1ms`)
4. One-field-absent — only one of the constrained pair is supplied

```python
import time
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _put(node, url, headers, body):
    r = req_lib.Request("PUT", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# Example: URL Shortener — validFrom < validTill constraint
# Replace with rules extracted from BUSINESS_RULES.md / x-business-rules
# ---------------------------------------------------------------------------

class TestBR_CF_001_validity_window_ordering:
    """validFrom must be strictly less than validTill when both are provided."""

    def test_valid_window_accepted(self, request, base_url, auth_headers):
        now  = int(time.time() * 1000)
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-happy",
            "validFrom": now + 60_000,
            "validTill": now + 3_600_000,
        })
        assert resp.status_code == 201

    def test_equal_from_and_till_rejected(self, request, base_url, auth_headers):
        ts   = int(time.time() * 1000) + 60_000
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-equal",
            "validFrom": ts,
            "validTill": ts,
        })
        assert resp.status_code == 400

    def test_from_after_till_rejected(self, request, base_url, auth_headers):
        now  = int(time.time() * 1000)
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-reversed",
            "validFrom": now + 3_600_000,
            "validTill": now + 60_000,
        })
        assert resp.status_code == 400

    def test_valid_till_in_past_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-past",
            "validTill": int(time.time() * 1000) - 1000,
        })
        assert resp.status_code == 400

    def test_only_valid_from_supplied_is_accepted(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-fromonly",
            "validFrom": int(time.time() * 1000) + 60_000,
        })
        assert resp.status_code == 201


# ---------------------------------------------------------------------------
# Example: IDGen — padding.length >= digits(sequence.start)
# ---------------------------------------------------------------------------

class TestBR_CF_002_padding_length_vs_sequence_start:
    """padding.length must be >= number of digits in sequence.start."""

    def test_padding_matches_start_width_accepted(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/v3/template", auth_headers, {
            "templateCode": "BR-CF-002-VALID",
            "config": {
                "template": "{SEQ}",
                "sequence": {
                    "scope":   "DAILY",
                    "start":   1000,
                    "padding": {"length": 4, "char": "0"},
                },
            },
        })
        try:
            assert resp.status_code == 201
        finally:
            req_lib.delete(
                f"{base_url}/v3/template"
                f"?templateCode=BR-CF-002-VALID&version=v1",
                headers=auth_headers,
            )

    def test_padding_shorter_than_start_width_rejected(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/v3/template", auth_headers, {
            "templateCode": "BR-CF-002-INVALID",
            "config": {
                "template": "{SEQ}",
                "sequence": {
                    "scope":   "DAILY",
                    "start":   1000,
                    "padding": {"length": 3, "char": "0"},
                },
            },
        })
        assert resp.status_code == 400
```

---

### `tests/test_cross_schema_rules.py`

Rules where entity A must exist before B can be created, or where deleting A
has a specified effect on B.

**Required pattern for every negative prerequisite test:**

```
1. GET current state of prerequisite A — assert it exists
2. DELETE A
3. try:
       assert that B operation fails as specified
   finally:
       POST to recreate A unconditionally
```

A missing `finally` restore breaks every subsequent test in the session.

```python
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _get(node, url, headers, params=None):
    r = req_lib.Request("GET", url, headers=headers, params=params)
    p = r.prepare(); attach_curl(node, p)
    return req_lib.Session().send(p)


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare(); attach_curl(node, p)
    return req_lib.Session().send(p)


def _delete(node, url, headers):
    r = req_lib.Request("DELETE", url, headers=headers)
    p = r.prepare(); attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# Example: URL Shortener — UrlConfig must exist before shortening
# ---------------------------------------------------------------------------

class TestBR_CS_001_config_required_before_shortening:
    """A URL cannot be shortened unless a UrlConfig exists for the tenant."""

    def test_shortening_succeeds_when_config_exists(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers,
                     {"url": "https://example.com/cs001-happy"})
        assert resp.status_code == 201

    def test_shortening_fails_when_config_absent(
        self, request, base_url, auth_headers
    ):
        existing = _get(request.node, f"{base_url}/v3/config", auth_headers)
        assert existing.status_code == 200, \
            "Precondition: UrlConfig must exist before this test can run"
        saved = existing.json()

        _delete(request.node, f"{base_url}/v3/config", auth_headers)
        try:
            resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers,
                         {"url": "https://example.com/cs001-neg"})
            assert resp.status_code == 404
        finally:
            _post(request.node, f"{base_url}/v3/config", auth_headers, {
                "shortKeyLength":     saved["shortKeyLength"],
                "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
            })

    def test_existing_short_urls_still_resolve_after_config_deleted(
        self, request, base_url, auth_headers
    ):
        shorten = _post(request.node, f"{base_url}/v3/short-url", auth_headers,
                        {"url": "https://example.com/cs001-persist"})
        assert shorten.status_code == 201
        short_key = shorten.json()["shortUrl"].split("/")[-1]

        existing = _get(request.node, f"{base_url}/v3/config", auth_headers)
        saved    = existing.json()
        _delete(request.node, f"{base_url}/v3/config", auth_headers)
        try:
            resolve = _get(request.node, f"{base_url}/{short_key}", auth_headers)
            assert resolve.status_code in (200, 307)
        finally:
            _post(request.node, f"{base_url}/v3/config", auth_headers, {
                "shortKeyLength":     saved["shortKeyLength"],
                "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
            })

    def test_post_config_409_when_already_exists(
        self, request, base_url, auth_headers
    ):
        existing = _get(request.node, f"{base_url}/v3/config", auth_headers)
        assert existing.status_code == 200, "Config must exist for this test"
        saved = existing.json()

        resp = _post(request.node, f"{base_url}/v3/config", auth_headers, {
            "shortKeyLength":     saved["shortKeyLength"],
            "maxShortKeyRetries": saved.get("maxShortKeyRetries", 10),
        })
        assert resp.status_code == 409


# ---------------------------------------------------------------------------
# Example: IDGen — duplicate template → 409
# ---------------------------------------------------------------------------

class TestBR_CS_002_duplicate_template_rejected:
    """Creating a template with an existing (tenantID, templateCode) returns 409."""

    def test_second_create_with_same_code_returns_409(
        self, request, base_url, auth_headers
    ):
        template_code = "BR-CS-002-DUP-TEST"

        first = _post(request.node, f"{base_url}/v3/template", auth_headers, {
            "templateCode": template_code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        assert first.status_code == 201

        try:
            second = _post(request.node, f"{base_url}/v3/template", auth_headers, {
                "templateCode": template_code,
                "config": {
                    "template": "{SEQ}",
                    "sequence": {"scope": "DAILY", "start": 1},
                },
            })
            assert second.status_code == 409
        finally:
            req_lib.delete(
                f"{base_url}/v3/template"
                f"?templateCode={template_code}&version=v1",
                headers=auth_headers,
            )
```

---

### `tests/test_lifecycle_rules.py`

State-transition rules: version increments, immutable fields, field
preservation across updates, delete cascades.

```python
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare(); attach_curl(node, p)
    return req_lib.Session().send(p)


def _put(node, url, headers, body):
    r = req_lib.Request("PUT", url, headers=headers, json=body)
    p = r.prepare(); attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# Example: IDGen — GLOBAL scope sequence.start is immutable after creation
# ---------------------------------------------------------------------------

class TestBR_LC_001_global_scope_start_immutable:
    """GLOBAL scope sequence.start cannot change after template creation."""

    def test_changing_global_start_on_update_rejected(
        self, request, base_url, auth_headers
    ):
        template_code = "BR-LC-001-TEST"

        create = _post(request.node, f"{base_url}/v3/template", auth_headers, {
            "templateCode": template_code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "GLOBAL", "start": 1},
            },
        })
        assert create.status_code == 201

        try:
            update = _put(request.node, f"{base_url}/v3/template", auth_headers, {
                "templateCode": template_code,
                "config": {
                    "template": "{SEQ}",
                    "sequence": {"scope": "GLOBAL", "start": 100},
                },
            })
            assert update.status_code == 422
        finally:
            req_lib.delete(
                f"{base_url}/v3/template"
                f"?templateCode={template_code}&version=v1",
                headers=auth_headers,
            )


# ---------------------------------------------------------------------------
# Example: IDGen — PUT preserves createdBy / createdTime from version 1
# ---------------------------------------------------------------------------

class TestBR_LC_002_update_preserves_audit_fields:
    """PUT must preserve createdBy and createdTime from the original version."""

    def test_created_fields_unchanged_after_update(
        self, request, base_url, auth_headers
    ):
        template_code = "BR-LC-002-TEST"

        create = _post(request.node, f"{base_url}/v3/template", auth_headers, {
            "templateCode": template_code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        assert create.status_code == 201
        v1 = create.json()

        try:
            update = _put(request.node, f"{base_url}/v3/template", auth_headers, {
                "templateCode": template_code,
                "config": {
                    "template": "{SEQ}-V2",
                    "sequence": {"scope": "DAILY", "start": 1},
                },
            })
            assert update.status_code == 201
            v2 = update.json()

            assert v2.get("createdBy")   == v1.get("createdBy")
            assert v2.get("createdTime") == v1.get("createdTime")
            assert v2.get("version")     == 2
        finally:
            for ver in ("v2", "v1"):
                req_lib.delete(
                    f"{base_url}/v3/template"
                    f"?templateCode={template_code}&version={ver}",
                    headers=auth_headers,
                )


# ---------------------------------------------------------------------------
# Example: URL Shortener — validity window evaluated at redirect time, not create time
# ---------------------------------------------------------------------------

class TestBR_LC_003_validity_window_evaluated_at_redirect:
    """validFrom and validTill are checked at redirect time, not at shorten time."""

    def test_url_not_yet_active_before_valid_from(
        self, request, base_url, auth_headers
    ):
        import time
        now        = int(time.time() * 1000)
        valid_from = now + 5_000  # 5 seconds in the future

        shorten = req_lib.post(
            f"{base_url}/v3/short-url",
            headers=auth_headers,
            json={"url": "https://example.com/lc003-future",
                  "validFrom": valid_from},
        )
        assert shorten.status_code == 201
        short_key = shorten.json()["shortUrl"].split("/")[-1]

        resolve = req_lib.get(f"{base_url}/{short_key}", headers=auth_headers,
                              allow_redirects=False)
        assert resolve.status_code == 400
        assert "NOT_YET_ACTIVE" in resolve.text or resolve.status_code == 400
```

---

### `tests/test_cross_module_rules.py`

Rules where this service calls another service, and the behaviour of the
second service affects the outcome here.

**Entire file is opt-in.** Each class checks for its required URL at runtime
and skips if absent. Never fail a CI run because a dependency URL was not
provided.

```python
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare(); attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# Example: Billing calls IDGen for bill number during bill creation
# ---------------------------------------------------------------------------

class TestBR_CM_001_billing_calls_idgen_for_bill_number:
    """
    Billing's POST /v3/bill must call IDGen POST /v3/generate to obtain a
    bill number. The bill number must be present and non-empty in the response.
    """

    @pytest.fixture(autouse=True)
    def skip_if_no_idgen(self, request):
        if not request.config.getoption("--idgen-url", default=None):
            pytest.skip("--idgen-url not provided; cross-module test skipped")

    def test_bill_contains_generated_bill_number(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/v3/bill", auth_headers,
                     _make_valid_bill_payload())
        assert resp.status_code == 201
        body = resp.json()
        assert "billNumber" in body
        assert body["billNumber"], "billNumber must be non-empty"


# ---------------------------------------------------------------------------
# Helper — replace with valid bill payload for the actual service
# ---------------------------------------------------------------------------

def _make_valid_bill_payload():
    return {
        "consumerCode":  "TEST-CONSUMER-001",
        "businessService": "TEST.SERVICE",
        "billDetails": [],
    }
```

---

## `seed_manifest.yaml` — format reference

```yaml
# services/<svc-name>/seed_manifest.yaml
prerequisites:
  - id: SEED-001
    description: UrlConfig must exist for the test tenant
    service: self                # "self" = use --base-url
    base_url_arg: "--base-url"
    check:
      method: GET
      path: /url-shortener/v3/config
      expect_status: 200
    create:
      method: POST
      path: /url-shortener/v3/config
      body: {shortKeyLength: 6, maxShortKeyRetries: 10}

  - id: SEED-002
    description: Bill number template must exist in IDGen
    service: idgen
    base_url_arg: "--idgen-url"   # URL for the IDGen service (not this service)
    check:
      method: GET
      path: /idgen/v3/template
      params: {templateCode: "${IDGEN_BILL_NUMBER_TEMPLATE_CODE}"}
      expect_status: 200
    create:
      method: POST
      path: /idgen/v3/template
      body:
        templateCode: "${IDGEN_BILL_NUMBER_TEMPLATE_CODE}"
        config:
          template: "{DATE:yyyymmdd}-{SEQ}"
          sequence: {scope: DAILY, start: 1, padding: {length: 5, char: "0"}}
```

---

## `env_map.yaml` — format reference

```yaml
# services/<svc-name>/env_map.yaml
# Resolves ${VAR_NAME} tokens used in seed_manifest.yaml and BUSINESS_RULES.md.
IDGEN_BILL_NUMBER_TEMPLATE_CODE:    "BILL-NUMBER"
IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE: "RECEIPT-NUMBER"
```

---

## `generate_rule_coverage_table.py`

```python
"""
Cross-references x-business-rules from schema.yaml against test class names.
Writes reports/rule_coverage_table.md.
Run: python generate_rule_coverage_table.py
"""
import yaml, os, re


def collect_rule_ids_from_spec(schema_path):
    with open(schema_path) as f:
        spec = yaml.safe_load(f)
    ids = []
    for path_item in spec.get("paths", {}).values():
        for op in path_item.values():
            if isinstance(op, dict):
                for rule in op.get("x-business-rules", []):
                    ids.append((
                        rule.get("id",        "UNNAMED"),
                        rule.get("title",     ""),
                        rule.get("test-type", ""),
                    ))
    return ids


def collect_covered_slugs(test_dir):
    covered = set()
    for root, _, files in os.walk(test_dir):
        for fname in files:
            if not fname.startswith("test_") or not fname.endswith(".py"):
                continue
            with open(os.path.join(root, fname)) as f:
                src = f.read()
            for match in re.findall(r"class TestBR_([A-Z0-9_]+)_", src):
                covered.add(match)
    return covered


def generate(schema_path="schema.yaml", test_dir="tests",
             output="reports/rule_coverage_table.md"):
    rules   = collect_rule_ids_from_spec(schema_path)
    covered = collect_covered_slugs(test_dir)

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w") as f:
        f.write("# Business rule test coverage\n\n")
        f.write("| Rule ID | Title | Type | Covered |\n")
        f.write("|---------|-------|------|---------|\n")
        for rid, title, rtype in rules:
            slug = rid.replace("-", "_").lstrip("BR_").lstrip("br_")
            ok   = "✅" if slug in covered else "❌"
            f.write(f"| {rid} | {title} | {rtype} | {ok} |\n")
    print(f"Written {output}")


if __name__ == "__main__":
    generate()
```

---

## CI integration — one job per service

```yaml
name: Business logic tests
on: [push, pull_request]

jobs:
  url-shortener-business-logic:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-python@v4
        with: {python-version: "3.11"}
      - run: pip install pytest requests jsonschema pytest-html pyyaml
      - working-directory: services/url-shortener
        run: |
          pytest tests/ \
            --base-url=http://url-shortener:8098 \
            --api-token=${{ secrets.API_TOKEN }} \
            --schema-token=${{ secrets.SCHEMA_TOKEN }} \
            --html=reports/business_logic.html \
            --self-contained-html
      - working-directory: services/url-shortener
        run: python generate_rule_coverage_table.py
      - uses: actions/upload-artifact@v3
        with:
          name: url-shortener-bl-report
          path: |
            services/url-shortener/reports/business_logic.html
            services/url-shortener/reports/rule_coverage_table.md

  billing-business-logic:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-python@v4
        with: {python-version: "3.11"}
      - run: pip install pytest requests jsonschema pytest-html pyyaml
      - working-directory: services/billing
        run: |
          pytest tests/ \
            --base-url=http://billing:8080 \
            --api-token=${{ secrets.API_TOKEN }} \
            --schema-token=${{ secrets.SCHEMA_TOKEN }} \
            --idgen-url=http://idgen:8082 \
            --html=reports/business_logic.html \
            --self-contained-html
      - working-directory: services/billing
        run: python generate_rule_coverage_table.py
      - uses: actions/upload-artifact@v3
        with:
          name: billing-bl-report
          path: |
            services/billing/reports/business_logic.html
            services/billing/reports/rule_coverage_table.md
```

`--self-contained-html` bundles all CSS/JS into a single file — required for
CI artifact portability.

`SCHEMA_TOKEN` must be a fine-grained GitHub PAT with **Contents: Read-only**
access to the repo hosting shared spec components.

---

## Key rules

1. `--base-url` is always a CLI argument — never hardcoded
2. `--base-url` absent → immediate failure; all other args optional
3. Never pass `X-Tenant-ID` — the gateway overrides it from the token anyway
4. Every test class maps to exactly one named rule ID
5. Test class naming: `TestBR_<CATEGORY>_<NNN>_<slug>`
6. Never duplicate what the conformance suite already checks (shapes, basic
   status codes)
7. Cross-field tests must include the boundary case, not just happy and error
8. Destructive tests (those that delete a prerequisite) MUST restore in
   `try/finally` — a missing restore breaks the rest of the session
9. Seeds are idempotent — 409 on create = success, not failure
10. Cross-module tests are opt-in — skip gracefully when dep URL is absent
11. Never run in parallel — `addopts = -p no:randomly` is mandatory
12. `attach_curl(request.node, prepared)` called BEFORE every `session.send()`
13. cURL block appears only on failed tests — zero noise on passing tests
14. `--self-contained-html` is required in CI for portable single-file reports
15. `schema.resolved.yaml` is a build artifact — never commit it
16. Never use `raw.githubusercontent.com` for private repos — always use
    the GitHub Contents API with `Accept: application/vnd.github.raw+json`
17. Run `generate_rule_coverage_table.py` as a post-test step in CI;
    upload the output alongside the HTML report
18. `${VAR_NAME}` tokens in seed bodies are resolved from `env_map.yaml` first,
    then OS environment — never hardcode template codes or similar values
19. If no `x-business-rules` annotations exist in the spec, derive IDs from
    section headings in `BUSINESS_RULES.md` using `BR-<CATEGORY>-<NNN>`
20. When `BUSINESS_RULES.md` mentions operational details (DB sequence names,
    Kafka topics, Flyway migrations, OTEL spans), ignore them — they are not
    test targets