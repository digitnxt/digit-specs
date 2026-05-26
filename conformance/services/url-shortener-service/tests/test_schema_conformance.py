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

_service_root = pathlib.Path(__file__).parent.parent
_resolved = _service_root / "schema.resolved.yaml"
_schema_file = _resolved if _resolved.exists() else _service_root / "schema.yaml"

schema = schemathesis.openapi.from_path(_schema_file)

# Checks skipped for infrastructure / gateway reasons:
#   ignored_auth              — base_url unavailable for secondary request when schema loaded from file
#   response_headers_conformance — infrastructure layer may omit Allow header on 405; not a service bug
#   negative_data_rejection   — X-Tenant-ID enforced by gateway, not by service layer
#   missing_required_header   — gateway injects X-Tenant-ID from token; absence → success by design
#   unsupported_method        — infrastructure handles 405; service never sees the request
_SKIPPED_CHECKS = [
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
]

# Endpoints that return 4xx for field-valid data due to state/ordering constraints
# rather than schema invalidity. positive_data_acceptance is skipped for these.
_STATE_DEPENDENT_ENDPOINTS = {
    "POST /v3/short-url",  # requires URL config to exist for the tenant
    "POST /v3/config",     # returns 409 if config already exists
    "GET /{key}",          # success is 307 — positive_data_acceptance only recognises 2xx
}


@schema.parametrize()
def test_all_endpoints_conform(case: Case, request, base_url, auth_headers, gateway_headers_spec, valid_short_key):
    if auth_headers:
        case.headers = {**(case.headers or {}), **auth_headers}

    # Substitute a real short key so redirect tests return 307 instead of 404.
    # Schemathesis generates random key values which never exist in the database.
    if case.path == "/{key}" and case.method.upper() == "GET":
        case.path_parameters = {"key": valid_short_key}

    try:
        # Redirect endpoint returns 307 — disable auto-follow so Schemathesis sees the raw response
        response = case.call(base_url=base_url, allow_redirects=False)
    except (UnicodeEncodeError, InvalidHeader):
        # Schemathesis occasionally generates header values with non-latin-1 or control
        # characters that the HTTP transport layer rejects before sending. Skip rather than fail.
        return

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    excluded = list(_SKIPPED_CHECKS)
    operation_key = f"{case.method.upper()} {case.path}"
    if operation_key in _STATE_DEPENDENT_ENDPOINTS:
        excluded.append(positive_data_acceptance)

    case.validate_response(response, excluded_checks=excluded)
    assert_gateway_headers(response, gateway_headers_spec)
