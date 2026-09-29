import os
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
from tests.helpers.curl_builder import build_curl

schema = schemathesis.openapi.from_path(
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "schema.yaml")
)

# Phase selection — keep `examples` (spec-provided examples) + `fuzzing` (random
# property-based generation); drop `coverage` and `stateful`.
#
#   coverage:  eagerly ENUMERATES edge cases at parametrize/collection time. The
#              RBAC `path` / JBAC `pathPattern` fields use a regex that repeats an
#              unbounded-segment alternation up to 20× (maxLength 256). Enumerating
#              that regex makes collection take ~2 min even with the bulk endpoints
#              excluded (measured 112s) — the single create/update endpoints are
#              nearly as expensive as the array-of-100 bulk ones. The fuzzing phase
#              still exercises the same malformed-input → 4xx contract, just
#              non-deterministically rather than exhaustively.
#   stateful:  drives UNCONTROLLED create/delete link sequences with fuzzer-invented
#              rule bodies (arbitrary paths/roles) — on this service that risks
#              shadowing or deleting the rule governing the service's own /access
#              (Go) / /access-java (Java) route, breaking Kong RBAC for the run.
#              Stateful CRUD is covered safely in tests/test_stateful_flows.py,
#              which only touches ids it just created.
schema.config.phases.update(phases=["examples", "fuzzing"])

# Endpoints skipped by Schemathesis parametrize. ALL delete operations are
# skipped: the fuzzer cannot be constrained to avoid deleting a REAL rule — in
# particular the rule that governs the accesscontrol service's own route
# (/access for Go, /access-java for Java). Deleting that rule breaks Kong RBAC
# for the service itself (404/403), cascading through the whole run. The
# tenant-delete additionally wipes ALL rules at once. Delete IS covered
# safely in the behavioral suite, which deletes only the id it just created.
_SKIP_ENDPOINTS = {
    "DELETE /rbac/rules/tenant",   # wipes ALL tenant rules
    "DELETE /jbac/rules/tenant",   # wipes ALL tenant rules
    "DELETE /rbac/rules/{id}",     # fuzzed id could hit a real rule (e.g. the /access rule)
    "DELETE /jbac/rules/{id}",     # fuzzed id could hit a real rule
}

_SKIP_PATH_PREFIXES = (
    "/internal/",  # Kong-plugin-only; not reachable via public gateway
    # "/jbac/",    # commented out — JBAC enabled for this run
)


@schema.parametrize()
def test_all_endpoints_conform(case, request, base_url, auth_headers):
    op_key = f"{case.operation.method.upper()} {case.operation.path}"
    if op_key in _SKIP_ENDPOINTS:
        pytest.skip(f"destructive endpoint skipped: {op_key}")
    if case.operation.path.startswith(_SKIP_PATH_PREFIXES):
        pytest.skip(f"internal-only endpoint skipped: {case.operation.path}")

    # Schema is loaded from a local file (base URL = file://…); set the real
    # base_url on the config so any check that re-issues the request internally
    # resolves against the gateway instead of raising IncorrectUsage.
    schema.config.base_url = base_url

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
    # NOTE: gateway operational headers (e.g. X-Kong-Request-Id) are intentionally
    # NOT asserted here — the fuzzer generates adversarial requests (bad methods,
    # malformed headers) that nginx rejects before Kong, so those responses
    # legitimately lack the Kong header. Gateway-header presence is validated in
    # the behavioral suite on well-formed requests instead.
    case.validate_response(response, excluded_checks=[
        ignored_auth,
        response_headers_conformance,
        negative_data_rejection,
        positive_data_acceptance,
        missing_required_header,
        unsupported_method,
        content_type_conformance,
    ])
