"""
Response-shape validators for the Individual Service.

The new spec drops the legacy `Individual` / `Individuals` envelope —
- POST/GET/PUT /individuals/{id} returns the Individual object directly.
- GET /individuals returns IndividualSearchResponse with `individuals` (lowercase).
- GET /individuals/exists returns ExistsResponse: `{exists: bool}`.
- POST/GET /configs returns ConfigResponse.
"""

import jsonschema

GENDER_VALUES = {"MALE", "FEMALE", "OTHER"}

IDENTIFIER_TYPE_VALUES = {
    "NATIONAL_ID", "AADHAAR", "PASSPORT", "VOTER_ID",
    "PAN", "DRIVING_LICENSE", "SYSTEM_GENERATED",
}

ADDRESS_TYPE_VALUES = {"PERMANENT", "CORRESPONDENCE"}

UNIQUENESS_CRITERIA_VALUES = {"mobileNumber", "name"}

# Headers Kong adds to every proxied 2xx response, verified against a live
# gateway response. These are gateway-injected — the individual service itself
# only sets X-Request-Id — so they are only meaningful when the suite runs
# through Kong (the intended target).
KONG_RESPONSE_HEADERS = ["X-Response-Time", "X-Response-Timestamp",
                         "X-Tenant-ID", "X-User-ID", "X-Kong-Request-Id"]


# ── Generic helpers ───────────────────────────────────────────────────────────

def assert_gateway_headers(response, gateway_headers_spec):
    if not gateway_headers_spec:
        return
    for header, spec in gateway_headers_spec.items():
        present = header in response.headers
        if spec["required"]:
            assert present, (
                f"Expected gateway header '{header}' missing. "
                f"Is the service behind the correct gateway?"
            )
        if present:
            value = response.headers[header]
            if spec["type"] == int:
                assert value.isdigit(), (
                    f"Gateway header '{header}' must be numeric, got: '{value}'"
                )
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0, (
                    f"Gateway header '{header}' must be non-empty string, got: '{value}'"
                )


def assert_service_response_headers(response):
    """Assert the headers Kong adds to every proxied 2xx response.

    X-Response-Time is a string with a unit suffix (e.g. "19.00ms");
    X-Response-Timestamp is epoch millis (numeric).
    """
    for h in KONG_RESPONSE_HEADERS:
        assert h in response.headers, f"Expected gateway response header '{h}' missing"
    ts = response.headers["X-Response-Timestamp"]
    assert ts.isdigit(), f"X-Response-Timestamp should be epoch millis, got: '{ts}'"


def assert_json_content_type(response):
    ct = response.headers.get("Content-Type", "")
    assert "application/json" in ct, f"Expected application/json Content-Type, got: '{ct}'"


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)


def assert_error_response(response):
    """Validate the spec-mandated error envelope: a JSON array of Error objects.

    Per the spec, every 4xx/5xx response body is `type: array, items: Error`.
    Examples of allowed shape:
        [{"code": "User.NotFound", "message": "User not found", ...}]
    Disallowed (legacy single-object shape) — will fail this assertion:
        {"code": "User.NotFound", "message": "User not found", ...}
    """
    assert_json_content_type(response)
    body = response.json()
    assert isinstance(body, list), (
        f"error response body must be a JSON array of Error objects, "
        f"got {type(body).__name__}: {body!r}"
    )
    assert len(body) >= 1, "error response array must contain at least one Error"
    for i, err in enumerate(body):
        assert isinstance(err, dict), \
            f"error[{i}] must be a JSON object, got {type(err).__name__}"
        # Per spec, code and message are required on Error
        assert "code" in err, f"error[{i}] missing required 'code' field: {err!r}"
        assert "message" in err, f"error[{i}] missing required 'message' field: {err!r}"
        assert isinstance(err["code"], str) and err["code"], \
            f"error[{i}].code must be a non-empty string, got {err['code']!r}"
        assert isinstance(err["message"], str) and err["message"], \
            f"error[{i}].message must be a non-empty string, got {err['message']!r}"


def assert_required_fields(body, fields):
    for field in fields:
        assert field in body, f"Required field '{field}' missing from response body"


def assert_field_types(body, type_map):
    for field, expected_type in type_map.items():
        if field in body and body[field] is not None:
            assert isinstance(body[field], expected_type), (
                f"Field '{field}' expected {expected_type.__name__}, "
                f"got {type(body[field]).__name__}: {body[field]!r}"
            )


def assert_enum_values(body, enum_map):
    for field, allowed in enum_map.items():
        if field in body and body[field] is not None:
            assert body[field] in allowed, (
                f"Field '{field}' value '{body[field]}' not in allowed: {allowed}"
            )


# ── Individual validators ─────────────────────────────────────────────────────

def assert_individual_shape(individual):
    """Validate required fields and types on an Individual object.

    Per spec, server-side response includes `id`, `givenName`, and `gender`
    at minimum. PII fields (mobileNumber, email, address) are decrypted on
    authorised reads.
    """
    assert isinstance(individual, dict), \
        f"Individual must be a JSON object, got {type(individual).__name__}"
    # `id` is server-generated, always present after create
    assert "id" in individual, "Individual missing required field 'id'"
    assert "givenName" in individual, "Individual missing required field 'givenName'"
    assert "gender" in individual, "Individual missing required field 'gender'"

    assert isinstance(individual["id"], str) and individual["id"], \
        "individual.id must be a non-empty string"
    assert isinstance(individual["givenName"], str) and individual["givenName"], \
        "individual.givenName must be a non-empty string"
    assert individual["gender"] in GENDER_VALUES, \
        f"individual.gender must be one of {GENDER_VALUES}, got '{individual['gender']}'"

    # Optional but typed fields — validate when present
    if "identifiers" in individual and individual["identifiers"] is not None:
        assert isinstance(individual["identifiers"], list), "identifiers must be a list"
        seen_types = set()
        for ident in individual["identifiers"]:
            assert "identifierType" in ident, "identifier missing identifierType"
            assert ident["identifierType"] in IDENTIFIER_TYPE_VALUES, \
                f"identifierType '{ident['identifierType']}' not in {IDENTIFIER_TYPE_VALUES}"
            # Spec: each identifierType may appear at most once per individual
            assert ident["identifierType"] not in seen_types, \
                f"duplicate identifierType '{ident['identifierType']}' in response"
            seen_types.add(ident["identifierType"])

    if "address" in individual and individual["address"] is not None:
        assert isinstance(individual["address"], list), "address must be a list"
        for addr in individual["address"]:
            if "type" in addr and addr["type"] is not None:
                assert addr["type"] in ADDRESS_TYPE_VALUES, \
                    f"address.type '{addr['type']}' not in {ADDRESS_TYPE_VALUES}"

    if "documents" in individual and individual["documents"] is not None:
        assert isinstance(individual["documents"], list), "documents must be a list"

    if "isActive" in individual:
        assert isinstance(individual["isActive"], bool), "isActive must be boolean"


def assert_individual_search_response(body):
    """Validate IndividualSearchResponse: totalCount, page, size, hasMore, individuals."""
    assert_required_fields(
        body,
        ["totalCount", "page", "size", "hasMore", "individuals"],
    )
    assert_field_types(body, {
        "totalCount": int,
        "page": int,
        "size": int,
        "hasMore": bool,
        "individuals": list,
    })
    assert body["totalCount"] >= 0, "totalCount must be non-negative"
    assert body["page"] >= 1, "page is 1-indexed; must be >= 1"
    assert 1 <= body["size"] <= 100, f"size must be 1..100, got {body['size']}"
    for ind in body["individuals"]:
        assert_individual_shape(ind)


# ── ExistsResponse validator ──────────────────────────────────────────────────

def assert_exists_response(body):
    """Validate ExistsResponse: {exists: bool}."""
    assert_required_fields(body, ["exists"])
    assert isinstance(body["exists"], bool), \
        f"exists must be boolean, got {type(body['exists']).__name__}"


# ── Config validators ────────────────────────────────────────────────────────

def assert_config_response(body):
    """Validate ConfigResponse — all fields optional except shape.

    Per spec, the response may carry mobileRegex, nameRegex,
    uniquenessCriteria, version, requestId, auditDetail. None are required
    individually (an empty/cleared config returns an object with no regexes).
    """
    assert isinstance(body, dict), \
        f"ConfigResponse must be a JSON object, got {type(body).__name__}"
    if "mobileRegex" in body and body["mobileRegex"] is not None:
        assert isinstance(body["mobileRegex"], str), "mobileRegex must be string"
    if "nameRegex" in body and body["nameRegex"] is not None:
        assert isinstance(body["nameRegex"], str), "nameRegex must be string"
    if "uniquenessCriteria" in body and body["uniquenessCriteria"] is not None:
        assert isinstance(body["uniquenessCriteria"], list), \
            "uniquenessCriteria must be a list"
        for item in body["uniquenessCriteria"]:
            # Per spec, response enum is restricted to {mobileNumber, name}
            assert item in UNIQUENESS_CRITERIA_VALUES, \
                f"uniquenessCriteria item '{item}' not in {UNIQUENESS_CRITERIA_VALUES}"
    if "version" in body and body["version"] is not None:
        assert isinstance(body["version"], int), "version must be integer"
