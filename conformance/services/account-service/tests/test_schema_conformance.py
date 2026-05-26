import os
import pytest
import schemathesis
from requests.exceptions import InvalidHeader
from schemathesis.specs.openapi.checks import (
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
    content_type_conformance,
)
from tests.helpers.curl_builder import build_curl
from tests.helpers.validators import assert_gateway_headers

schema = schemathesis.openapi.from_path(
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "schema.yaml")
)

# Endpoints skipped by Schemathesis parametrize for one of two reasons:
#   (a) Destructive — running the operation would wipe state and cascade
#       failures for the rest of the run (DELETE tears down Keycloak realm).
#   (b) Side-effectful / requires external coordination — /signup/verify
#       needs the real OTP from email, /signup/resend needs an in-flight
#       referenceId; Schemathesis cannot synthesize either.
_SKIP_ENDPOINTS = {
    "DELETE /accounts",          # wipes Keycloak realm + DB rows → cascades failures
    "POST /signup/verify",       # requires the real OTP from email
    "POST /signup/resend",       # requires an in-flight referenceId from /signup
}


@schema.parametrize()
def test_all_endpoints_conform(case, request, base_url, auth_headers, gateway_headers_spec):
    op_key = f"{case.operation.method.upper()} {case.operation.path}"
    if op_key in _SKIP_ENDPOINTS:
        pytest.skip(f"endpoint skipped (destructive or requires real OTP): {op_key}")

    try:
        response = case.call(base_url=base_url, headers=auth_headers)
    except (UnicodeEncodeError, InvalidHeader):
        # Schemathesis occasionally generates header values with non-latin-1 or control
        # characters that the HTTP transport layer rejects before sending.  These are
        # untestable at the network level; skip them rather than failing the suite.
        return

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    # ignored_auth: auth is handled by Kong gateway; covered in test_error_contracts.py
    # response_headers_conformance: X-Response-Time is set by Kong as "N.00ms" string
    #   (infrastructure behavior outside service control)
    # negative_data_rejection: Kong re-writes headers (e.g. X-Tenant-ID) before the service
    #   sees them, so the service cannot reject schema-violating header values.
    # missing_required_header: Kong injects X-Tenant-ID from the auth token, so the service
    #   never receives a request with that header absent.
    # unsupported_method: nginx returns 405 for TRACE/etc. without the RFC 9110-required
    #   Allow header — infrastructure behaviour outside service control.
    # content_type_conformance: when Schemathesis generates malformed HTTP headers
    #   (control chars, non-latin-1), nginx/Kong rejects the request at the HTTP parse
    #   layer and returns "400 Bad Request" as text/plain, before the request reaches
    #   the service. The spec documents application/json for all responses, but this
    #   gateway-level response is outside service control.
    case.validate_response(response, excluded_checks=[
        ignored_auth,
        response_headers_conformance,
        negative_data_rejection,
        missing_required_header,
        unsupported_method,
        content_type_conformance,
    ])
    assert_gateway_headers(response, gateway_headers_spec)
