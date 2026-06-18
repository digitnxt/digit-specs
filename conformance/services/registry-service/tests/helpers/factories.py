import uuid


def _uid():
    return uuid.uuid4().hex[:8].upper()


def make_schema_code():
    return f"test-schema-{uuid.uuid4().hex[:8]}"


def make_schema_request(schema_code=None, **overrides):
    """Valid SchemaRequest. Required: schemaCode, definition."""
    code = schema_code or make_schema_code()
    base = {
        "schemaCode": code,
        "definition": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "name":  {"type": "string"},
                "value": {"type": "string"},
            },
            "required": ["name"],
        },
    }
    return {**base, **overrides}


def make_schema_update(schema_code, **overrides):
    """Updated schema definition — adds a new optional field."""
    base = {
        "schemaCode": schema_code,
        "definition": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "name":        {"type": "string"},
                "value":       {"type": "string"},
                "description": {"type": "string"},
            },
            "required": ["name"],
        },
    }
    return {**base, **overrides}


def make_data_request(**overrides):
    """Valid DataRequest. Required: data."""
    base = {"data": {"name": f"Record-{_uid()}", "value": f"Value-{_uid()}"}}
    return {**base, **overrides}


def make_search_request(**overrides):
    """SearchRequest with optional filters/contains/limit/offset."""
    base = {"limit": 20, "offset": 0}
    return {**base, **overrides}


def make_is_exist_request(value=None, field=None):
    """IsExistRequest. Required: value."""
    req = {"value": value or f"test-{_uid()}"}
    if field:
        req["field"] = field
    return req


# ── Invalid payloads ──────────────────────────────────────────────────────────

def make_invalid_schema_request(strategy="missing_required"):
    strategies = {
        "missing_required":    {},
        "missing_schema_code": {"definition": {"type": "object"}},
        "missing_definition":  {"schemaCode": make_schema_code()},
        "empty_body":          {},
    }
    return strategies.get(strategy, {})


def make_invalid_data_request(strategy="missing_required"):
    strategies = {
        "missing_required": {},
        "missing_data":     {"version": 1},
        "empty_body":       {},
    }
    return strategies.get(strategy, {})
