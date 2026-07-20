import jsonschema


def assert_gateway_headers(response, gateway_headers_spec):
    if not gateway_headers_spec:
        return
    for header, spec in gateway_headers_spec.items():
        present = header in response.headers
        if spec["required"]:
            assert present, f"Expected gateway header '{header}' is missing."
        if present:
            value = response.headers[header]
            if spec["type"] == int:
                assert value.isdigit(), f"Header '{header}' should be numeric, got: '{value}'"
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0, \
                    f"Header '{header}' should be non-empty string, got: '{value}'"


def assert_json_content_type(response):
    ct = response.headers.get("Content-Type", "")
    assert "application/json" in ct, f"Expected application/json Content-Type, got: '{ct}'"


def assert_service_response_headers(response):
    """
    Headers Kong adds to every proxied 2xx response, verified against a live
    gateway response. These are gateway-injected, not emitted by the Go service
    itself — so this assertion is only meaningful when the suite runs through
    Kong (the intended target).

      X-Response-Time       e.g. "19.00ms"     (string with unit suffix)
      X-Response-Timestamp  e.g. "1783937512767" (epoch millis, numeric)
      X-Tenant-ID           tenant resolved from the token
      X-User-ID             user resolved from the token
      X-Kong-Request-Id     per-request correlation id
    """
    for header in ("X-Response-Time", "X-Response-Timestamp",
                   "X-Tenant-ID", "X-User-ID", "X-Kong-Request-Id"):
        assert header in response.headers, f"Expected gateway response header '{header}' missing"

    ts = response.headers["X-Response-Timestamp"]
    assert ts.isdigit(), f"X-Response-Timestamp should be epoch millis, got: '{ts}'"


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)


def assert_error_array(body):
    """
    Every error response from the service is a JSON array of error objects,
    each carrying at least a string `code` and `message` (see pkg/errors +
    httputil.WriteError). Returns the first error for further assertions.
    """
    assert isinstance(body, list), f"Error body must be a JSON array, got {type(body).__name__}"
    assert len(body) >= 1, "Error array must contain at least one error object"
    first = body[0]
    assert isinstance(first, dict), "Each error entry must be an object"
    assert isinstance(first.get("code"), str) and first["code"], "Error 'code' must be a non-empty string"
    assert isinstance(first.get("message"), str) and first["message"], "Error 'message' must be a non-empty string"
    return first


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
    for field, allowed_values in enum_map.items():
        if field in body and body[field] is not None:
            assert body[field] in allowed_values, (
                f"Field '{field}' value '{body[field]}' not in allowed: {allowed_values}"
            )


def assert_bare_array(body):
    """
    Search endpoints (GET /employees, GET /employees/{id}/jurisdictions) return
    a bare JSON array of resource objects — NOT a paginated wrapper. This
    replaces the old assert_pagination_shape.
    """
    assert isinstance(body, list), (
        f"Search response must be a bare JSON array, got {type(body).__name__}: {body!r}"
    )
    return body


def assert_boundary_relation(boundary_relation):
    """
    boundaryRelation is an array of {code, boundaryType, hierarchyType} objects
    (see the Boundary schema / models.BoundaryRef).
    """
    assert isinstance(boundary_relation, list), "boundaryRelation must be an array"
    for entry in boundary_relation:
        assert isinstance(entry, dict), "each boundaryRelation entry must be an object"
        for key in ("code", "boundaryType", "hierarchyType"):
            assert key in entry, f"boundaryRelation entry missing '{key}'"
            assert isinstance(entry[key], str) and entry[key], f"boundaryRelation '{key}' must be a non-empty string"
