import jsonschema


def assert_required_fields(body, fields):
    for field in fields:
        assert field in body, f"Required field '{field}' missing from response"


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


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)
