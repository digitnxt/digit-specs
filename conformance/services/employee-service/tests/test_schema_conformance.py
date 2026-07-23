import os
import schemathesis
import schemathesis.openapi
from schemathesis import Case
from requests.exceptions import InvalidHeader
from schemathesis.specs.openapi.checks import (
    ignored_auth,
    response_headers_conformance,
    content_type_conformance,
    positive_data_acceptance,
    negative_data_rejection,
    missing_required_header,
    unsupported_method,
)
from tests.helpers.curl_builder import build_curl

_schema_path = os.path.join(os.path.dirname(__file__), "..", "schema.yaml")
schema = schemathesis.openapi.from_path(os.path.abspath(_schema_path))

# These checks are excluded because they fire on gateway/environment artifacts
# or on intentional platform behaviour, not on real contract violations.
# Everything else (response_schema_conformance, status_code_conformance,
# not_a_server_error, …) stays active and DOES surface genuine problems.
#
# - positive_data_acceptance (RejectedPositiveData): schemathesis replays the
#   spec's inline `examples`, which reference fictional entities that don't exist
#   in a real tenant — e.g. boundaryRelation `STATE33d / state-district-city`,
#   placeholder `userId` / `individualId`. The service correctly rejects them
#   (400), but the schema can't encode which boundary codes are valid in a given
#   tenant, so this is a false positive against a live environment.
#
# - content_type_conformance (UndefinedContentType): when schemathesis generates
#   a request with an illegal character in a header, the servlet container
#   (Tomcat) rejects it at the HTTP protocol layer — before the app runs — and
#   serves its default `text/html` "HTTP Status 400" page. That's correct
#   container behaviour for a malformed request, not an application response the
#   OpenAPI contract governs.
#
# - missing_required_header (MissingHeaderNotRejected): Kong injects the headers
#   the spec marks client-required (X-Tenant-ID, X-User-ID, …) from the auth
#   token, so a client omission never reaches the service as a rejection.
#
# - unsupported_method (UnsupportedMethodResponse): routing and auth happen at
#   Kong, so an undocumented HTTP method is denied/handled at the gateway before
#   reaching the service.
#
# - negative_data_rejection (AcceptedNegativeData): its only remaining hit is
#   UNKNOWN query parameters (e.g. ?foo=bar) returning 200 instead of 400 —
#   deliberate, uniform platform behaviour: all four implementations
#   (individual-go/-java, employee-go/-java) ignore unknown query params and
#   return 200. Invalid query *values* (bad bool/date, over-max, overflow,
#   non-numeric) ARE correctly rejected with 400 on all of them, and those cases
#   are asserted deterministically in tests/test_error_contracts.py. So this
#   check only flags by-design leniency, not a defect.
#
# - ignored_auth (IgnoredAuth): tries to re-send the request WITHOUT auth to
#   verify the service enforces it. Inapplicable here for two reasons — auth is
#   enforced at Kong (the service does none), and the suite injects the token via
#   headers on every call, so the check can't actually remove it → it sees 200
#   and false-reports "auth ignored." (This is also the check whose internal
#   re-issue surfaced the base_url quirk.)
#
# - response_headers_conformance: Kong sets timing/rate-limit response headers in
#   its own format (e.g. X-Response-Time: "28.00ms"), which doesn't match the
#   spec's declared header types. A gateway concern, not the service contract.
#
# This exclusion set matches the individual-service suite — both run through Kong
# and see the same gateway artifacts.
#
# Everything else stays active — notably response_schema_conformance,
# status_code_conformance, and not_a_server_error (which caught the offset/limit
# int32-overflow 500).
_EXCLUDED_CHECKS = [
    positive_data_acceptance,
    content_type_conformance,
    missing_required_header,
    unsupported_method,
    negative_data_rejection,
    ignored_auth,
    response_headers_conformance,
]


@schema.parametrize()
def test_all_endpoints_conform(case: Case, request, base_url, auth_headers):
    # The schema is loaded from a local file, so its own base URL is `file://…`.
    # Some checks re-issue the request internally (without our explicit base_url),
    # which would fall back to that file:// URL and raise IncorrectUsage. Setting
    # the base_url on the schema config makes every call — ours and the checks'
    # internal re-calls — resolve against the real gateway.
    schema.config.base_url = base_url
    try:
        response = case.call(base_url=base_url, headers=auth_headers)
    except (UnicodeEncodeError, InvalidHeader):
        # Schemathesis sometimes generates header values with non-latin-1 or
        # control characters that Python's HTTP client can't encode/send. The
        # request never leaves the client, so there's nothing to validate — skip.
        return
    if hasattr(response, "request") and response.request is not None:
        request.node._curl_request = response.request
    # NOTE: gateway operational headers (e.g. X-Kong-Request-Id) are intentionally
    # NOT asserted here. They are not part of the OpenAPI contract, and the fuzzer
    # generates adversarial requests (bad methods, malformed headers) that are
    # rejected by nginx *before* Kong — those responses legitimately lack the Kong
    # header. Gateway-header presence is validated in the behavioral suite on
    # well-formed requests instead.
    case.validate_response(response, excluded_checks=_EXCLUDED_CHECKS)
