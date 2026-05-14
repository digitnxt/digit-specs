import re
import jsonschema

SEQUENCE_SCOPES = {"GLOBAL", "DAILY", "MONTHLY", "YEARLY"}


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
                assert value.isdigit(), f"Gateway header '{header}' should be numeric, got: '{value}'"
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0


def assert_service_response_headers(response):
    for h in ("X-Response-Time", "X-Response-Timestamp", "X-Request-ID"):
        assert h in response.headers, f"Expected service header '{h}' missing"


def assert_json_content_type(response):
    assert "application/json" in response.headers.get("Content-Type", ""), (
        f"Expected application/json, got: {response.headers.get('Content-Type')}"
    )


def assert_required_fields(body, fields):
    for f in fields:
        assert f in body, f"Required field '{f}' missing from response"


def assert_field_types(body, type_map):
    for field, expected in type_map.items():
        if field in body and body[field] is not None:
            assert isinstance(body[field], expected), (
                f"'{field}' expected {expected.__name__}, got {type(body[field]).__name__}"
            )


def assert_enum_values(body, enum_map):
    for field, allowed in enum_map.items():
        if field in body and body[field] is not None:
            assert body[field] in allowed, (
                f"'{field}' value '{body[field]}' not in allowed: {allowed}"
            )


def assert_error_schema(body, schema):
    jsonschema.validate(instance=body, schema=schema)


def assert_template_response_shape(body):
    """Validates IDGenTemplateResponse required fields and types."""
    assert_required_fields(body, ["id", "templateCode", "version", "config"])
    assert_field_types(body, {"id": str, "templateCode": str, "version": str})
    assert re.match(r'^v\d+$', body.get("version", "")), (
        f"version must match ^v\\d+$, got: {body.get('version')}"
    )
    config = body.get("config", {})
    assert "template" in config, "config.template is required in response"


def assert_generate_response_shape(body):
    """Validates GenerateIDResponse required fields and types."""
    assert_required_fields(body, ["templateCode", "version", "id"])
    assert_field_types(body, {"templateCode": str, "version": str, "id": str})
    assert re.match(r'^v\d+$', body.get("version", "")), (
        f"version must match ^v\\d+$, got: {body.get('version')}"
    )
    assert len(body.get("id", "")) > 0, "Generated ID must not be empty"


def assert_bulk_generate_response_shape(body, expected_count):
    """Validates BulkGenerateIDResponse required fields, types, and count."""
    assert_required_fields(body, ["templateCode", "version", "count", "ids"])
    assert body["count"] == expected_count, (
        f"Response count {body['count']} != requested count {expected_count}"
    )
    assert isinstance(body["ids"], list), "ids must be an array"
    assert len(body["ids"]) == expected_count, (
        f"ids array length {len(body['ids'])} != requested count {expected_count}"
    )
    for idx, id_val in enumerate(body["ids"]):
        assert isinstance(id_val, str) and len(id_val) > 0, (
            f"ids[{idx}] must be a non-empty string, got: {id_val!r}"
        )
