import pathlib
import schemathesis
from schemathesis import Case
from tests.helpers.validators import assert_gateway_headers

_SCHEMA_PATH = pathlib.Path(__file__).parent.parent / "schema.yaml"
schema = schemathesis.openapi.from_path(_SCHEMA_PATH)


@schema.parametrize()
def test_all_endpoints_conform(case: Case, request, base_url, auth_headers, gateway_headers_spec):
    """
    Runs for every path + method declared in the spec.
    Validates: response schema, status codes, Content-Type.
    Attaches PreparedRequest to node so conftest renders cURL in conformance.html on failure.
    """
    response = case.call(base_url=base_url, headers=auth_headers)

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    case.validate_response(response)
    assert_gateway_headers(response, gateway_headers_spec)
