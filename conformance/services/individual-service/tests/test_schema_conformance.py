import pathlib
import pytest
import schemathesis
from requests.exceptions import InvalidHeader
from schemathesis.specs.openapi.checks import (
    ignored_auth,
    response_headers_conformance,
    negative_data_rejection,
    positive_data_acceptance,
    missing_required_header,
    unsupported_method,
    content_type_conformance,
)

# Schema file is `individual.yaml` (not the legacy `schema.yaml`).
_SCHEMA_PATH = pathlib.Path(__file__).parent.parent / "individual.yaml"
schema = schemathesis.openapi.from_path(_SCHEMA_PATH)

# Endpoints skipped by Schemathesis parametrize.
# DELETE is destructive — Schemathesis runs many iterations of a generated
# UUID; on the rare iteration where the UUID collides with a real record it
# would soft-delete a row used by other tests. Cleaner to exclude.
_SKIP_ENDPOINTS = {
    "DELETE /individuals/{id}",
}


@schema.parametrize()
def test_all_endpoints_conform(case, request, base_url, auth_headers):
    """Auto-generated conformance test for every (path, method) in the spec.

    Validates request/response against the schema, then asserts gateway
    headers if a gateway profile is configured.
    """
    # Schema is loaded from a local file (base URL = file://…); set the real
    # base_url on the config so checks that re-issue the request internally
    # resolve against the gateway instead of raising IncorrectUsage.
    schema.config.base_url = base_url

    op_key = f"{case.operation.method.upper()} {case.operation.path}"
    if op_key in _SKIP_ENDPOINTS:
        pytest.skip(f"destructive endpoint skipped: {op_key}")

    try:
        response = case.call(base_url=base_url, headers=auth_headers)
    except (UnicodeEncodeError, InvalidHeader):
        # Schemathesis occasionally generates header values with non-latin-1 or
        # control characters that the HTTP transport rejects before sending.
        # These are untestable at the network level; skip them.
        return

    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request

    # Excluded checks — all behaviours outside the strict
    # request-conforms / response-conforms contract:
    # - ignored_auth: auth is handled by the API gateway, not the service.
    # - response_headers_conformance: gateway sets timing headers in its own format.
    # - negative_data_rejection: gateway may rewrite/strip headers before they reach the service.
    # - positive_data_acceptance: Schemathesis 4 generates negative-mode
    #     boundary+1 values for positive testing (e.g. size=101 when
    #     maximum: 100). The service correctly returns the documented 400,
    #     but this check considers any non-{2xx, 401, 403, 404, 409, 5xx}
    #     response a rejection of valid data — false positives at every
    #     boundary. The explicit negative-input cases live in
    #     tests/test_error_contracts.py.
    # - missing_required_header: gateway injects X-Tenant-ID from the auth token.
    # - unsupported_method: nginx returns 405 for TRACE/etc. without the
    #     RFC 9110-required Allow header.
    # - content_type_conformance: when Schemathesis generates malformed HTTP
    #     headers (control chars, non-latin-1), nginx/Kong rejects the request
    #     at the HTTP parse layer and returns text/plain — before the service
    #     is reached.
    # NOTE: gateway operational headers (e.g. X-Kong-Request-Id) are intentionally
    # NOT asserted here. They aren't part of the OpenAPI contract, and the fuzzer
    # generates adversarial requests (bad methods, malformed headers) that nginx
    # rejects *before* Kong — those responses legitimately lack the Kong header.
    # Gateway-header presence is validated in the behavioral suite on well-formed
    # requests instead.
    case.validate_response(response, excluded_checks=[
        ignored_auth,
        response_headers_conformance,
        negative_data_rejection,
        positive_data_acceptance,
        missing_required_header,
        unsupported_method,
        content_type_conformance,
    ])
