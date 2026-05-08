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


def assert_shorten_response_shape(body):
    """ShortenResponse: required field is shortUrl (valid URI string)."""
    assert "shortUrl" in body, "ShortenResponse must contain 'shortUrl'"
    short_url = body["shortUrl"]
    assert isinstance(short_url, str) and len(short_url) > 0, \
        f"shortUrl must be a non-empty string, got: {short_url!r}"
    assert short_url.startswith("http"), \
        f"shortUrl must be an absolute URI, got: {short_url!r}"


def assert_redirect_response(response):
    """
    Redirect endpoint returns 307. When allow_redirects=False, we see the raw 307.
    Validates: status 307, Location header present and non-empty.
    """
    assert response.status_code == 307, \
        f"Expected 307 Temporary Redirect, got {response.status_code}: {response.text}"
    location = response.headers.get("Location", "")
    assert location, "307 response must include a non-empty Location header"
    assert location.startswith("http"), \
        f"Location header must be an absolute URI, got: {location!r}"
