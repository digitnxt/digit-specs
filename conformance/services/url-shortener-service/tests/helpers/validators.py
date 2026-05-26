import jsonschema


def assert_gateway_headers(response, gateway_headers_spec):
    if not gateway_headers_spec:
        return
    for header, spec in gateway_headers_spec.items():
        present = header in response.headers
        if spec["required"]:
            assert present, f"Expected gateway header '{header}' missing"
        if present:
            value = response.headers[header]
            if spec["type"] == int:
                assert value.isdigit(), f"Gateway header '{header}' should be numeric, got: '{value}'"
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0, \
                    f"Gateway header '{header}' should be a non-empty string, got: '{value}'"


def assert_service_response_headers(response):
    """Assert the three required service tracking headers on every 2xx response."""
    for h in ("X-Response-Time", "X-Response-Timestamp", "X-Request-ID"):
        assert h in response.headers, f"Expected service header '{h}' missing"


def assert_json_content_type(response):
    assert "application/json" in response.headers.get("Content-Type", ""), \
        f"Expected Content-Type application/json, got: {response.headers.get('Content-Type')}"


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


def assert_url_config_shape(body):
    """UrlConfig: required shortKeyLength (int 4–12), optional maxShortKeyRetries (int 1–20)."""
    assert "shortKeyLength" in body, "UrlConfig must contain 'shortKeyLength'"
    assert isinstance(body["shortKeyLength"], int), \
        f"shortKeyLength must be an integer, got: {type(body['shortKeyLength']).__name__}"
    assert 4 <= body["shortKeyLength"] <= 12, \
        f"shortKeyLength must be 4–12, got: {body['shortKeyLength']}"
    if "maxShortKeyRetries" in body and body["maxShortKeyRetries"] is not None:
        assert isinstance(body["maxShortKeyRetries"], int), \
            f"maxShortKeyRetries must be an integer, got: {type(body['maxShortKeyRetries']).__name__}"
        assert 1 <= body["maxShortKeyRetries"] <= 20, \
            f"maxShortKeyRetries must be 1–20, got: {body['maxShortKeyRetries']}"


def assert_delete_config_shape(body):
    """DELETE /v3/config response shape: {deleted: boolean}."""
    assert "deleted" in body, "Delete config response must contain 'deleted'"
    assert isinstance(body["deleted"], bool), \
        f"'deleted' must be a boolean, got: {type(body['deleted']).__name__}"


def assert_error_array(body):
    """Error responses are arrays of Error objects per the spec."""
    assert isinstance(body, list), \
        f"Error response body must be an array, got: {type(body).__name__}"
    for item in body:
        assert isinstance(item, dict), \
            f"Each error item must be an object, got: {type(item).__name__}"
