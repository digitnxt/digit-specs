# Claude Instructions: Generate OpenAPI Conformance Test Suite

## Objective

Generate a **conformance test suite** that validates whether a microservice implementation adheres strictly to a provided **OpenAPI 3.0 specification**.

Each microservice gets its **own isolated test suite** — independent folder, independent execution, independent CI job. No shared test files across services.

The test suite must:
- Validate request/response correctness
- Detect schema mismatches
- Verify status codes and headers (including gateway-injected headers)
- Include negative and edge case testing
- Be runnable in CI/CD pipelines

---

## Inputs

You will be given:
- OpenAPI 3.0 spec (YAML or JSON)
- Optional:
  - Base URL (passed as CLI arg — never hardcoded)
  - Authentication token (passed as CLI arg)
  - Gateway type (`kong`, `aws`, `custom`, or omitted)
  - Example test data

---

## Core Principles

1. **OpenAPI is the single source of truth**
   - Do NOT hardcode endpoints or schemas outside the spec
   - All tests must be derived from the spec

2. **Do not manually enumerate endpoints**
   - Automatically iterate over all paths and operations via Schemathesis

3. **Environment independence**
   - Base URL must be a required CLI argument — never hardcoded, never from `.env`, never with a default fallback
   - Omitting `--base-url` must cause immediate failure with a clear error

4. **Deterministic + generative testing**
   - Use both:
     - Examples from the spec (if provided)
     - Generated inputs via Schemathesis (for edge cases and fuzzing)

5. **No mocking**
   - Tests must hit the real running service — never mock responses

6. **Spec is immutable**
   - If a test fails, the service is wrong — never adjust tests to match broken behavior

---

## Tech Stack

| Tool | Role |
|---|---|
| **Python 3.11+** | Primary language |
| **Schemathesis** | Auto-generates contract tests from OpenAPI spec (property-based) |
| **pytest** | Test runner and reporting |
| **requests** | HTTP client for explicit assertion tests |
| **jsonschema** | Response schema validation |
| **pydantic** | Data model validation for response payloads |
| **pytest-html** | HTML report generation |

Install dependencies:
```bash
pip install schemathesis pytest requests jsonschema pydantic pytest-html
```

Alternative tooling (acceptable if explicitly requested):
- Dredd
- Postman/Newman

---

## CLI Arguments

All runtime configuration is passed as CLI arguments to `pytest`. Nothing is read from environment variables or config files.

| Argument | Required | Description |
|---|---|---|
| `--base-url` | **Yes** | Base URL of the service (e.g. `http://localhost:8080`) |
| `--api-token` | No | Bearer token for authenticated endpoints |
| `--gateway` | No | Gateway profile: `kong`, `aws`, or `custom`. Omit if no gateway |

### Registration in `conftest.py`

```python
def pytest_addoption(parser):
    parser.addoption(
        "--base-url",
        action="store",
        required=True,              # Hard fail if omitted — no default allowed
        help="Base URL of the service under test"
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
```

### Example invocations

```bash
cd services/user-service

# Minimal — no auth, no gateway
pytest tests/ -v --base-url=http://localhost:8080

# With auth
pytest tests/ -v --base-url=http://localhost:8080 --api-token=your-token

# Behind Kong gateway
pytest tests/ -v --base-url=http://api.prod.internal --api-token=your-token --gateway=kong

# Against staging, generate HTML report
pytest tests/ -v --base-url=http://user-service.staging.internal --gateway=aws --html=reports/conformance.html

# Omitting --base-url fails immediately — intentional
pytest tests/ -v    # ERROR: --base-url is required
```

---

## Expected Output Structure

Generate exactly this file layout for each microservice. Do not deviate from naming or placement.

```
services/
└── <service-name>/
    ├── schema.yaml                          # OpenAPI 3.0 spec (provided as input)
    ├── conftest.py                          # CLI args + all shared fixtures
    ├── pytest.ini                           # Test discovery config
    ├── tests/
    │   ├── __init__.py
    │   ├── test_schema_conformance.py       # Layer 1: Schemathesis auto-generated
    │   ├── test_response_contracts.py       # Layer 2: Explicit field/type assertions
    │   ├── test_error_contracts.py          # Layer 3: 4xx/5xx error schema validation
    │   ├── test_stateful_flows.py           # Layer 4: Multi-step workflow tests
    │   └── helpers/
    │       ├── __init__.py
    │       ├── validators.py                # assert_gateway_headers() and other helpers
    │       └── factories.py                 # Request payload builders
    └── reports/                             # Generated reports — gitignore this folder
```

> **Rule:** Never place test files outside the service folder. No cross-service imports. One `conftest.py` per service, never at the repo root.

---

## `conftest.py` — Full Implementation

Generate this file exactly as shown. All fixtures are session-scoped.

```python
# services/<service-name>/conftest.py
import pytest
import schemathesis

# --- Gateway header profiles ---
# Add header specs per gateway type.
# required=True  → assert header is present in every response
# required=False → assert type only if header happens to be present
# type           → int (must be numeric string) or str
# Do NOT assert the actual values of rate limit headers — only presence and type.

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
    "custom": {
        # Extend with your own gateway headers here
    },
}


def pytest_addoption(parser):
    parser.addoption(
        "--base-url",
        action="store",
        required=True,
        help="Base URL of the service under test (e.g. http://localhost:8080)"
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


@pytest.fixture(scope="session")
def base_url(request):
    return request.config.getoption("--base-url").rstrip("/")


@pytest.fixture(scope="session")
def auth_headers(request):
    token = request.config.getoption("--api-token")
    return {"Authorization": f"Bearer {token}"} if token else {}


@pytest.fixture(scope="session")
def gateway_headers_spec(request):
    """
    Returns the header spec dict for the active gateway profile,
    or an empty dict if --gateway is not provided (no-op mode).
    """
    gateway = request.config.getoption("--gateway")
    return GATEWAY_HEADER_PROFILES.get(gateway, {})


@pytest.fixture(scope="session")
def swagger_schema(base_url):
    return schemathesis.from_file("schema.yaml", base_url=base_url)
```

---

## `pytest.ini` — Per Service

```ini
# services/<service-name>/pytest.ini
[pytest]
testpaths = tests
addopts = -v
```

---

## `helpers/validators.py` — Reusable Assertions

```python
# services/<service-name>/tests/helpers/validators.py
import jsonschema


def assert_gateway_headers(response, gateway_headers_spec):
    """
    Validates gateway-injected headers against the active gateway profile.
    If no gateway profile is active (empty spec), this is a no-op.

    Rules:
    - required=True  → header must be present in the response
    - required=False → only validated if present
    - type=int       → value must be a numeric string
    - type=str       → value must be a non-empty string
    - NEVER assert the actual numeric value of rate limit headers
    """
    if not gateway_headers_spec:
        return  # No gateway configured — skip silently

    for header, spec in gateway_headers_spec.items():
        present = header in response.headers

        if spec["required"]:
            assert present, (
                f"Expected gateway header '{header}' is missing from response. "
                f"Is the service running behind the correct gateway?"
            )

        if present:
            value = response.headers[header]
            if spec["type"] == int:
                assert value.isdigit(), (
                    f"Gateway header '{header}' should be a numeric string, got: '{value}'"
                )
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0, (
                    f"Gateway header '{header}' should be a non-empty string, got: '{value}'"
                )


def assert_error_schema(body, error_schema):
    """Validates a response body against the declared error schema."""
    jsonschema.validate(instance=body, schema=error_schema)


def assert_required_fields(body, fields):
    """Asserts all listed field names are present in response body."""
    for field in fields:
        assert field in body, f"Required field '{field}' missing from response body"


def assert_field_types(body, type_map):
    """
    Asserts field types. type_map = {"fieldName": expected_python_type}
    Example: {"id": str, "age": int, "active": bool}
    """
    for field, expected_type in type_map.items():
        if field in body:
            assert isinstance(body[field], expected_type), (
                f"Field '{field}' expected type {expected_type.__name__}, "
                f"got {type(body[field]).__name__}: {body[field]!r}"
            )


def assert_enum_values(body, enum_map):
    """
    Asserts field values are within declared enum sets.
    enum_map = {"status": {"active", "inactive", "pending"}}
    """
    for field, allowed_values in enum_map.items():
        if field in body:
            assert body[field] in allowed_values, (
                f"Field '{field}' value '{body[field]}' not in allowed values: {allowed_values}"
            )
```

---

## `helpers/factories.py` — Payload Builders

```python
# services/<service-name>/tests/helpers/factories.py

def make_valid_payload(**overrides):
    """
    Returns a minimal valid request payload for the primary resource.
    Override individual fields by passing keyword arguments.
    Populate defaults based on the OpenAPI spec's required fields and examples.
    """
    base = {
        # Replace with actual required fields from the spec
        "name": "Test Resource",
        "email": "test@example.com",
    }
    return {**base, **overrides}


def make_invalid_payload(strategy="missing_required"):
    """
    Returns an intentionally invalid payload for negative testing.
    Strategies:
      - "missing_required" : empty body {}
      - "wrong_type"       : field values with incorrect types
      - "invalid_enum"     : enum field set to an undeclared value
    """
    strategies = {
        "missing_required": {},
        "wrong_type":       {"name": 12345, "email": False},
        "invalid_enum":     {"status": "not-a-valid-status"},
    }
    return strategies.get(strategy, {})
```

---

## Test Categories

### Layer 1 — Schema Conformance (Schemathesis) `test_schema_conformance.py`

Auto-generates test cases for every endpoint + method in the spec. Always generate this layer in full.

```python
# services/<service-name>/tests/test_schema_conformance.py
import schemathesis
from schemathesis import Case

schema = schemathesis.from_file("../schema.yaml")


@schema.parametrize()
def test_all_endpoints_conform(case: Case, base_url, auth_headers, gateway_headers_spec):
    """
    Runs for every path + method in the spec.
    Validates: response schema, status codes, Content-Type header.
    Also validates gateway headers if --gateway is specified.
    """
    from tests.helpers.validators import assert_gateway_headers

    response = case.call(base_url=base_url, headers=auth_headers)
    case.validate_response(response)
    assert_gateway_headers(response, gateway_headers_spec)
```

---

### Layer 2 — Explicit Response Contract Tests `test_response_contracts.py`

Manually written assertions for critical endpoints. Derive field names, types, and enum values directly from the OpenAPI spec — do not invent them.

```python
# services/<service-name>/tests/test_response_contracts.py
import pytest
import requests
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_required_fields,
    assert_field_types,
    assert_enum_values,
)


class TestGetResourceContract:
    def test_success_response_shape(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/<path>/<valid-id>", headers=auth_headers)

        assert response.status_code == 200
        assert "application/json" in response.headers.get("Content-Type", "")

        body = response.json()

        # Derive these from the spec's `required` field list
        assert_required_fields(body, ["id", "name", "createdAt"])

        # Derive from the spec's `type` declarations
        assert_field_types(body, {"id": str, "name": str})

        # Derive from the spec's `enum` declarations
        assert_enum_values(body, {"status": {"active", "inactive", "pending"}})

        # Gateway headers — no-op if --gateway not set
        assert_gateway_headers(response, gateway_headers_spec)

    def test_not_found_returns_404(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/<path>/nonexistent-id", headers=auth_headers)

        assert response.status_code == 404
        body = response.json()
        assert_required_fields(body, ["error", "message"])
        assert_gateway_headers(response, gateway_headers_spec)
```

---

### Layer 3 — Negative / Error Contract Tests `test_error_contracts.py`

Test all invalid input scenarios. Derive the expected error schema from the spec's error response definitions.

```python
# services/<service-name>/tests/test_error_contracts.py
import pytest
import requests
from tests.helpers.validators import assert_error_schema, assert_gateway_headers
from tests.helpers.factories import make_invalid_payload

# Derive this schema from the spec's declared error response body
ERROR_SCHEMA = {
    "type": "object",
    "required": ["error", "message"],
    "properties": {
        "error":   {"type": "string"},
        "message": {"type": "string"},
        "details": {"type": "array", "items": {"type": "string"}},
    },
    "additionalProperties": False,
}


class TestNegativeContracts:
    def test_missing_required_fields_returns_422(self, base_url, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/<path>",
            json=make_invalid_payload("missing_required")
        )
        assert response.status_code == 422
        assert_error_schema(response.json(), ERROR_SCHEMA)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_wrong_field_types_returns_422(self, base_url, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/<path>",
            json=make_invalid_payload("wrong_type")
        )
        assert response.status_code == 422
        assert_error_schema(response.json(), ERROR_SCHEMA)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_enum_value_returns_422(self, base_url, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/<path>",
            json=make_invalid_payload("invalid_enum")
        )
        assert response.status_code == 422
        assert_error_schema(response.json(), ERROR_SCHEMA)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, base_url, gateway_headers_spec):
        response = requests.get(f"{base_url}/<protected-path>")
        assert response.status_code == 401
        assert_error_schema(response.json(), ERROR_SCHEMA)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, base_url, gateway_headers_spec):
        response = requests.get(
            f"{base_url}/<protected-path>",
            headers={"Authorization": "Bearer invalid-token"}
        )
        assert response.status_code == 401
        assert_error_schema(response.json(), ERROR_SCHEMA)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_path_param_invalid_format(self, base_url, auth_headers, gateway_headers_spec):
        # Test with an invalid format for the path parameter (e.g. non-UUID where UUID expected)
        response = requests.get(f"{base_url}/<path>/!!!invalid!!!", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)
```

---

### Layer 4 — Stateful Flow Tests `test_stateful_flows.py`

Test chained API calls that reflect real lifecycle workflows. Always clean up resources created during the test.

```python
# services/<service-name>/tests/test_stateful_flows.py
import pytest
import requests
from tests.helpers.factories import make_valid_payload
from tests.helpers.validators import assert_gateway_headers


class TestResourceLifecycle:
    def test_create_read_update_delete(self, base_url, auth_headers, gateway_headers_spec):
        resource_id = None
        try:
            # 1. CREATE
            create_resp = requests.post(
                f"{base_url}/<path>",
                json=make_valid_payload(),
                headers=auth_headers
            )
            assert create_resp.status_code == 201
            assert_gateway_headers(create_resp, gateway_headers_spec)
            resource_id = create_resp.json()["id"]

            # 2. READ — must exist immediately after creation
            get_resp = requests.get(f"{base_url}/<path>/{resource_id}", headers=auth_headers)
            assert get_resp.status_code == 200
            assert get_resp.json()["id"] == resource_id
            assert_gateway_headers(get_resp, gateway_headers_spec)

            # 3. UPDATE — PUT or PATCH per spec
            update_resp = requests.patch(
                f"{base_url}/<path>/{resource_id}",
                json={"name": "Updated Name"},
                headers=auth_headers
            )
            assert update_resp.status_code in (200, 204)
            assert_gateway_headers(update_resp, gateway_headers_spec)

            # 4. DELETE
            del_resp = requests.delete(f"{base_url}/<path>/{resource_id}", headers=auth_headers)
            assert del_resp.status_code == 204
            assert_gateway_headers(del_resp, gateway_headers_spec)
            resource_id = None  # Mark as cleaned up

            # 5. CONFIRM DELETION — must return 404
            after_resp = requests.get(f"{base_url}/<path>/nonexistent-id", headers=auth_headers)
            assert after_resp.status_code == 404

        finally:
            # Safety net cleanup — runs even if an assertion fails mid-test
            if resource_id:
                requests.delete(f"{base_url}/<path>/{resource_id}", headers=auth_headers)
```

---

## Assertion Checklist

Apply all of the following to every response test:

| Concern | Rule |
|---|---|
| **Status code** | Must match the spec's declared code for the scenario |
| **Content-Type** | Must match spec declaration — typically `application/json` |
| **Required fields** | Every field marked `required` in the spec must be present |
| **Data types** | Exact match — no coercion. Strings are not integers. |
| **Enum values** | Only values declared in the spec's `enum` list are valid |
| **Nullable fields** | `null` is only valid where spec declares `nullable: true` |
| **Date/time formats** | Must be ISO 8601 where `format: date-time` is declared |
| **Pagination shape** | `page`, `pageSize`, `total` must be present on paginated endpoints |
| **Gateway headers** | Presence + type only — never assert rate limit numeric values |
| **Error schema** | All 4xx/5xx bodies must conform to the spec's error schema |

---

## Gateway Header Validation

Gateway headers are injected by the infrastructure layer, not the service. They must be tested conditionally based on the `--gateway` CLI argument.

### Rules

- **Assert presence and type only** — never assert the numeric value of rate limit headers (e.g. do not assert `X-RateLimit-Limit-Minute == 100`). Limit values are configuration, not contract.
- **`required: True` headers** must appear in every response when that gateway profile is active
- **`required: False` headers** are validated only if present in the response
- **No gateway (`--gateway` omitted)** → `assert_gateway_headers()` is a silent no-op. No test changes needed.
- To add a new gateway, add a new key to `GATEWAY_HEADER_PROFILES` in `conftest.py` — no other files need changes.

### Gateway profiles (defined in `conftest.py`)

| Gateway | Key Headers |
|---|---|
| `kong` | `X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`, `X-Kong-Request-Id` |
| `aws` | `x-amzn-RequestId`, `x-amz-apigw-id` |
| `custom` | Define in `GATEWAY_HEADER_PROFILES["custom"]` |

---

## Adding a New Microservice

1. Create `services/<new-service>/`
2. Place the OpenAPI spec at `services/<new-service>/schema.yaml`
3. Copy `conftest.py` and `pytest.ini` verbatim — they are service-agnostic
4. Generate all four test layers under `services/<new-service>/tests/`
5. Populate `helpers/factories.py` with required fields from the spec
6. Run: `cd services/<new-service> && pytest tests/ -v --base-url=http://<host>`

---

## CI Integration — One Job Per Service

```yaml
# .github/workflows/conformance.yml
name: Conformance Tests
on: [push, pull_request]

jobs:
  user-service:
    runs-on: ubuntu-latest
    services:
      user-service:
        image: your-org/user-service:latest
        ports: ["8080:8080"]
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-python@v4
        with:
          python-version: "3.11"
      - run: pip install schemathesis pytest requests jsonschema pydantic pytest-html
      - working-directory: services/user-service
        run: |
          pytest tests/ \
            --base-url=http://localhost:8080 \
            --api-token=${{ secrets.API_TOKEN }} \
            --gateway=kong \
            --html=reports/conformance.html
      - uses: actions/upload-artifact@v3
        with:
          name: user-service-report
          path: services/user-service/reports/

  order-service:
    runs-on: ubuntu-latest
    services:
      order-service:
        image: your-org/order-service:latest
        ports: ["8081:8081"]
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-python@v4
        with:
          python-version: "3.11"
      - run: pip install schemathesis pytest requests jsonschema pydantic pytest-html
      - working-directory: services/order-service
        run: |
          pytest tests/ \
            --base-url=http://localhost:8081 \
            --api-token=${{ secrets.API_TOKEN }} \
            --gateway=aws \
            --html=reports/conformance.html
      - uses: actions/upload-artifact@v3
        with:
          name: order-service-report
          path: services/order-service/reports/
```

---

## Key Rules

1. **`--base-url` is always a CLI argument** — never hardcoded, never from `.env`, never with a default
2. **`--base-url` omitted = immediate failure** — `required=True` in `pytest_addoption` is non-negotiable
3. **`--gateway` omitted = silent no-op** — gateway assertions are skipped, no code changes needed
4. **Never assert rate limit values** — assert presence and type of gateway headers only
5. **One service folder, one test suite** — no cross-service imports or shared test files
6. **One assertion concern per test function** — do not mix schema, type, and business logic in one test
7. **Always clean up stateful tests** — use `try/finally` to delete created resources even on failure
8. **Use `scope="session"` for all fixtures** — resolve URL, token, gateway, and schema once per run
9. **Do not mock the service** — conformance tests must hit the real running service
10. **Treat the spec as immutable** — if a test fails, fix the service, not the test