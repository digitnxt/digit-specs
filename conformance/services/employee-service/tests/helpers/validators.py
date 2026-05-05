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
    for header in ("X-Response-Time", "X-Response-Timestamp", "X-Request-ID"):
        assert header in response.headers, f"Expected service header '{header}' missing"


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)


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


def assert_pagination_shape(body, items_key):
    assert items_key in body, f"Pagination response missing '{items_key}' array"
    assert isinstance(body[items_key], list), f"'{items_key}' must be an array"
    assert "totalCount" in body, "Pagination response missing 'totalCount'"
    assert "page" in body, "Pagination response missing 'page'"
    assert "size" in body, "Pagination response missing 'size'"
    assert "hasMore" in body, "Pagination response missing 'hasMore'"
    assert isinstance(body["totalCount"], int), "'totalCount' must be integer"
    assert isinstance(body["hasMore"], bool), "'hasMore' must be boolean"
