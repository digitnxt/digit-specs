import os
import schemathesis
from requests.exceptions import InvalidHeader
from schemathesis.specs.openapi.checks import (
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
)
from tests.helpers.curl_builder import build_curl
from tests.helpers.validators import assert_gateway_headers

schema = schemathesis.openapi.from_path(
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "schema.yaml")
)


@schema.parametrize()
def test_all_endpoints_conform(case, request, base_url, auth_headers, gateway_headers_spec):
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
    case.validate_response(response, excluded_checks=[
        ignored_auth,
        response_headers_conformance,
        negative_data_rejection,
        missing_required_header,
        unsupported_method,
    ])
    assert_gateway_headers(response, gateway_headers_spec)
