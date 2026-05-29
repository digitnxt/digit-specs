import re
import jsonschema

# Valid values from the spec
TEMPLATE_TYPES     = {"EMAIL", "SMS"}
SMS_CATEGORIES     = {"OTP", "TRANSACTION", "PROMOTION", "NOTIFICATION", "OTHERS"}
NOTIFICATION_STATI = {"QUEUED", "SENT"}
VERSION_RE         = re.compile(r'^v[1-9][0-9]*$')
UUID_RE            = re.compile(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', re.IGNORECASE
)


# ---------------------------------------------------------------------------
# Infrastructure validators
# ---------------------------------------------------------------------------

def assert_gateway_headers(response, gateway_headers_spec):
    if not gateway_headers_spec:
        return
    for header, spec in gateway_headers_spec.items():
        present = header in response.headers
        if spec["required"]:
            assert present, f"Required gateway header '{header}' missing"
        if present:
            value = response.headers[header]
            if spec["type"] == int:
                assert value.isdigit(), \
                    f"Gateway header '{header}' should be numeric, got: '{value}'"
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0, \
                    f"Gateway header '{header}' should be a non-empty string, got: '{value}'"


def assert_service_response_headers(response):
    """Verify standard service-layer response headers are present."""
    for h in ("X-Response-Time", "X-Response-Timestamp", "X-Request-ID"):
        assert h in response.headers, f"Expected service header '{h}' missing"


def assert_json_content_type(response):
    assert "application/json" in response.headers.get("Content-Type", ""), (
        f"Expected application/json, got: {response.headers.get('Content-Type')}"
    )


# ---------------------------------------------------------------------------
# Generic field helpers
# ---------------------------------------------------------------------------

def assert_required_fields(body, fields):
    for f in fields:
        assert f in body, f"Required field '{f}' missing from response"


def assert_field_types(body, type_map):
    for field, expected in type_map.items():
        if field in body and body[field] is not None:
            assert isinstance(body[field], expected), (
                f"'{field}' expected {expected.__name__}, "
                f"got {type(body[field]).__name__}: {body[field]!r}"
            )


def assert_enum_values(body, enum_map):
    for field, allowed in enum_map.items():
        if field in body and body[field] is not None:
            assert body[field] in allowed, (
                f"'{field}' value '{body[field]}' not in allowed: {allowed}"
            )


def assert_error_schema(body, schema):
    jsonschema.validate(instance=body, schema=schema)


# ---------------------------------------------------------------------------
# Domain-specific shape validators
# ---------------------------------------------------------------------------

def assert_template_response_shape(body):
    """Validate a single Template object from the spec."""
    assert_required_fields(body, ["templateId", "type"])
    assert_field_types(body, {"templateId": str, "type": str, "content": str})

    if "id" in body and body["id"] is not None:
        assert UUID_RE.match(body["id"]), \
            f"Template 'id' must be a UUID, got: {body['id']!r}"

    if "version" in body and body["version"] is not None:
        assert VERSION_RE.match(body["version"]), \
            f"Template 'version' must match ^v[1-9][0-9]*$, got: {body['version']!r}"

    assert body["type"] in TEMPLATE_TYPES, \
        f"Template 'type' must be EMAIL or SMS, got: {body['type']!r}"


def assert_notification_response_shape(body):
    """Validate a NotificationResponse object from the spec."""
    assert_required_fields(body, ["templateId", "status"])
    assert_field_types(body, {"templateId": str, "status": str})

    if "version" in body and body["version"] is not None:
        assert VERSION_RE.match(body["version"]), \
            f"NotificationResponse 'version' must match ^v[1-9][0-9]*$, got: {body['version']!r}"

    assert body["status"] in NOTIFICATION_STATI, \
        f"NotificationResponse 'status' must be QUEUED or SENT, got: {body['status']!r}"


def assert_preview_response_shape(body):
    """Validate a TemplatePreviewResponse object from the spec."""
    assert_required_fields(body, ["templateId", "type"])
    assert_field_types(body, {"templateId": str, "type": str})

    if "version" in body and body["version"] is not None:
        assert VERSION_RE.match(body["version"]), \
            f"Preview 'version' must match ^v[1-9][0-9]*$, got: {body['version']!r}"

    assert body["type"] in TEMPLATE_TYPES, \
        f"Preview 'type' must be EMAIL or SMS, got: {body['type']!r}"

    # renderedContent is always expected when preview succeeds
    assert "renderedContent" in body, \
        "TemplatePreviewResponse must contain 'renderedContent'"
    assert isinstance(body["renderedContent"], str) and len(body["renderedContent"]) > 0, \
        "Preview 'renderedContent' must be a non-empty string"


def assert_error_array(body):
    """
    Error responses in this service are arrays of Error objects.
    Each Error has at minimum: code (str) and message (str).
    """
    assert isinstance(body, list), \
        f"Error response must be an array, got {type(body).__name__}: {body!r}"
    assert len(body) > 0, "Error response array must not be empty"
    for i, err in enumerate(body):
        assert isinstance(err, dict), f"errors[{i}] must be an object"
        assert "message" in err or "code" in err, \
            f"errors[{i}] must contain at least 'code' or 'message', got: {err}"
