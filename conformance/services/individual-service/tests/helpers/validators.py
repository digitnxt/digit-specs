import jsonschema

GENDER_VALUES     = {"MALE", "FEMALE", "OTHER", "PREFER_NOT_TO_SAY"}

SERVICE_RESPONSE_HEADERS = ["X-Response-Time", "X-Request-ID"]


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
    for h in SERVICE_RESPONSE_HEADERS:
        assert h in response.headers, f"Expected service header '{h}' missing"


def assert_json_content_type(response):
    ct = response.headers.get("Content-Type", "")
    assert "application/json" in ct, f"Expected application/json Content-Type, got: '{ct}'"


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)


def assert_required_fields(body, fields):
    for field in fields:
        assert field in body, f"Required field '{field}' missing from response body"


def assert_field_types(body, type_map):
    for field, expected_type in type_map.items():
        if field in body:
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
