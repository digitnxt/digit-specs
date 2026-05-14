import pathlib
import schemathesis
from requests.exceptions import InvalidHeader
from schemathesis import Case
from schemathesis.specs.openapi.checks import (
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
    positive_data_acceptance,
)
from tests.helpers.validators import assert_gateway_headers

_SCHEMA_PATH = pathlib.Path(__file__).parent.parent / "schema.yaml"
schema = schemathesis.openapi.from_path(_SCHEMA_PATH)

# Checks skipped for infrastructure / gateway reasons:
#   ignored_auth              — base_url unavailable for secondary request when schema loaded from file
#   response_headers_conformance — nginx returns 405 without Allow header (RFC 9110); nginx issue, not service
#   negative_data_rejection   — X-Tenant-ID / X-User-ID enforced by gateway, not by service layer
#   missing_required_header   — gateway injects X-Tenant-ID from token; absence → 404 by design
#   unsupported_method        — nginx handles 405; service never sees the request
_SKIPPED_CHECKS = [
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
]

# Endpoints with cross-field or relational constraints that Schemathesis cannot
# satisfy with field-valid generated data:
#   POST /generate      — templateCode must reference an existing template
#   POST /generate/bulk — same; count=0 or count>1000 will also fail validation
#   DELETE /template    — templateCode + version must reference an existing version
#   PUT /template       — templateCode must reference an existing template to update
_CROSS_FIELD_ENDPOINTS = {
    "POST /generate",
    "POST /generate/bulk",
    "DELETE /template",
    "PUT /template",
}


@schema.parametrize()
def test_all_endpoints_conform(case: Case, request, base_url, auth_headers, gateway_headers_spec):
    """
    Auto-generates tests for every path + method in the spec.
    Validates: response schema, status codes, Content-Type.
    Attaches PreparedRequest to node so conftest renders cURL in conformance.html on failure.
    """
    if auth_headers:
        case.headers = {**(case.headers or {}), **auth_headers}

    try:
        response = case.call(base_url=base_url)
    except (UnicodeEncodeError, InvalidHeader):
        # Schemathesis occasionally generates header values with non-latin-1 or control
        # characters that the HTTP transport layer rejects before sending. Skip these.
        return

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    excluded = list(_SKIPPED_CHECKS)
    operation_key = f"{case.method.upper()} {case.path}"
    if operation_key in _CROSS_FIELD_ENDPOINTS:
        excluded.append(positive_data_acceptance)

    case.validate_response(response, excluded_checks=excluded)
    assert_gateway_headers(response, gateway_headers_spec)
