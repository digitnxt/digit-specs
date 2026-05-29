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

# Use schema.resolved.yaml (written by conftest.pytest_configure when
# --schema-token is supplied) so that private $ref components are resolved.
# Falls back to schema.yaml when no token is provided.
_service_root = pathlib.Path(__file__).parent.parent
_resolved     = _service_root / "schema.resolved.yaml"
_schema_file  = _resolved if _resolved.exists() else _service_root / "schema.yaml"

schema = schemathesis.openapi.from_path(_schema_file)

# Checks skipped for infrastructure / gateway reasons:
#   ignored_auth              — base_url unavailable for secondary request when schema loaded from file
#   response_headers_conformance — infrastructure layer may omit Allow header on 405
#   negative_data_rejection   — X-Tenant-ID enforced by gateway, not by service layer
#   missing_required_header   — gateway injects headers from token; absence → success by design
#   unsupported_method        — infrastructure handles 405; service never sees the request
_SKIPPED_CHECKS = [
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
]

# Endpoints whose 4xx rejections of Schemathesis-generated data reflect
# cross-field or relational constraints the generator cannot satisfy:
#   PUT /template         — templateId must reference an existing template
#   POST /template/preview — templateId must reference an existing template
#   POST /email/send      — templateId must reference an existing template
#   POST /sms/send        — templateId must reference an existing template
_CROSS_FIELD_ENDPOINTS = {
    "PUT /template",
    "POST /template/preview",
    "POST /email/send",
    "POST /sms/send",
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
        # Schemathesis occasionally generates header values with non-latin-1 or
        # control characters that the HTTP transport layer rejects. Skip these.
        return

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    excluded = list(_SKIPPED_CHECKS)
    operation_key = f"{case.method.upper()} {case.path}"
    if operation_key in _CROSS_FIELD_ENDPOINTS:
        excluded.append(positive_data_acceptance)

    case.validate_response(response, excluded_checks=excluded)
    assert_gateway_headers(response, gateway_headers_spec)
