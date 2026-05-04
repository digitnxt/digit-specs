import jsonschema

DEMAND_STATUSES  = {"DRAFT", "ACTIVE", "FROZEN", "PARTIALLY_PAID", "PAID", "ROLL_FORWARDED", "CANCELLED"}
BILL_STATUSES    = {"ACTIVE", "CANCELLED", "EXPIRED", "PAID", "PARTIALLY_PAID", "PAYMENT_CANCELLED"}
PAYMENT_STATUSES = {"NEW", "DEPOSITED", "CANCELLED", "DISHONOURED", "RECONCILED"}
COLLECTION_MODES = {"ONLINE", "OFFLINE", "COUNTER", "FIELD", "BOTH"}
TAX_HEAD_CATS    = {"TAX", "CESS", "PENALTY", "INTEREST", "REBATE", "ROUNDING", "ARREAR", "OTHER"}


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
