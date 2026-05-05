import os
import pytest
import schemathesis
from tests.helpers.curl_builder import build_curl
from tests.helpers.validators import assert_gateway_headers

schema = schemathesis.openapi.from_path(
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "schema.yaml")
)


@schema.parametrize()
def test_all_endpoints_conform(case, request, base_url, auth_headers, gateway_headers_spec):
    # Multipart file upload cannot be auto-generated — tested separately
    if case.path == "/upload" and case.method.upper() == "POST":
        pytest.skip("Multipart upload tested in test_response_contracts.py")

    response = case.call(base_url=base_url, headers=auth_headers)

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    case.validate_response(response)
    assert_gateway_headers(response, gateway_headers_spec)
