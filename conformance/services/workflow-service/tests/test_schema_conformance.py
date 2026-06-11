import pathlib
import pytest
import schemathesis
from hypothesis import HealthCheck, settings
from schemathesis import Case
from schemathesis import checks as st_checks
from tests.helpers.validators import assert_gateway_headers

_SCHEMA_PATH = pathlib.Path(__file__).parent.parent / "schema.yaml"
schema = schemathesis.openapi.from_path(_SCHEMA_PATH)


@schema.parametrize()
@settings(suppress_health_check=[HealthCheck.filter_too_much])
def test_all_endpoints_conform(case: Case, request, base_url, auth_headers, gateway_headers_spec):
    """
    Runs for every path + method declared in the spec.
    Validates: response schema, status codes, Content-Type.
    Attaches PreparedRequest to node so conftest renders cURL in conformance.html on failure.
    """
    headers = {**auth_headers}
    if "Authorization" not in headers:
        raise AssertionError("Missing auth token for schema conformance run")
    try:
        response = case.call(base_url=base_url, headers=headers)
    except UnicodeEncodeError as e:
        raise AssertionError(f"Header encoding error during schema case: {e}")

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    # Intentionally skip header-focused checks (missing/unknown headers, unsupported methods)
    # to keep this suite focused on service-level contract behavior.
    case.validate_response(
        response,
        checks=(
            st_checks.status_code_conformance,
            st_checks.response_schema_conformance,
            st_checks.content_type_conformance,
            st_checks.not_a_server_error,
        ),
    )
    assert_gateway_headers(response, gateway_headers_spec)
