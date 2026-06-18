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


def assert_registry_data_shape(data):
    """Validates a RegistryData object has all required fields."""
    required = ["id", "registryId", "schemaCode", "schemaVersion", "version", "data",
                "isActive", "effectiveFrom", "auditDetails"]
    for field in required:
        assert field in data, f"RegistryData missing required field '{field}'"
    assert isinstance(data["data"], dict), "RegistryData.data must be an object"
    assert isinstance(data["isActive"], bool), "RegistryData.isActive must be boolean"


def assert_schema_shape(schema):
    """Validates a Schema object has all required fields."""
    required = ["id", "version", "isLatest", "isActive", "auditDetails"]
    for field in required:
        assert field in schema, f"Schema missing required field '{field}'"
    assert isinstance(schema["isLatest"], bool), "Schema.isLatest must be boolean"
    assert isinstance(schema["isActive"], bool), "Schema.isActive must be boolean"
    assert isinstance(schema["version"], int) and schema["version"] >= 1, \
        "Schema.version must be a positive integer"
