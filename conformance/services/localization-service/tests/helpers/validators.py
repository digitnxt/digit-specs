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
                    f"Header '{header}' should be a non-empty string, got: '{value}'"


def assert_service_response_headers(response):
    content_type = response.headers.get("Content-Type", "")
    assert "application/json" in content_type, \
        f"Expected Content-Type application/json, got: {content_type}"


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


def assert_message_shape(msg):
    """Validate optional fields of a Message object when present."""
    for field in ("uuid", "module", "locale", "code", "message"):
        if field in msg:
            assert isinstance(msg[field], str), \
                f"Message field '{field}' must be a string, got {type(msg[field]).__name__}"


def assert_messages_response(body, key="messages"):
    """Validate that body has a list of well-formed messages."""
    assert key in body, f"Response must contain '{key}' field"
    assert isinstance(body[key], list), f"'{key}' must be a list"
    for msg in body[key]:
        assert_message_shape(msg)


def assert_find_missing_response(body):
    """FindMissingMessagesResponse is a map of module -> locale -> list of string codes."""
    assert isinstance(body, dict), "FindMissingMessagesResponse must be an object"
    for module, locale_map in body.items():
        assert isinstance(module, str), f"Module key must be a string, got {type(module).__name__}"
        assert isinstance(locale_map, dict), \
            f"Value for module '{module}' must be an object (locale map), got {type(locale_map).__name__}"
        for locale, codes in locale_map.items():
            assert isinstance(locale, str), \
                f"Locale key under module '{module}' must be a string, got {type(locale).__name__}"
            assert isinstance(codes, list), \
                f"Missing codes for module '{module}', locale '{locale}' must be a list"
            for code in codes:
                assert isinstance(code, str), \
                    f"Each missing code must be a string, got {type(code).__name__}: {code!r}"


def assert_delete_response(body):
    assert "success" in body, "DeleteMessagesResponse must have 'success' field"
    assert isinstance(body["success"], bool), "'success' must be a boolean"


def assert_cache_bust_response(body):
    assert "success" in body, "CacheBustResponse must have 'success' field"
    assert isinstance(body["success"], bool), "'success' must be a boolean"
    if "message" in body:
        assert isinstance(body["message"], str), "'message' must be a string"
