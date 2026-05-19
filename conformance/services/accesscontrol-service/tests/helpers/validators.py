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


def assert_json_content_type(response):
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


# ── RBAC validators ────────────────────────────────────────────────────────

def assert_rbac_rule_shape(rule):
    """Validate required fields and types on an RbacRule object."""
    required = ["id", "roleNames", "httpMethod", "path", "effect", "priority", "enabled"]
    for field in required:
        assert field in rule, f"RBAC rule missing required field '{field}'"
    assert isinstance(rule["id"], str) and rule["id"], \
        "rule.id must be a non-empty string"
    assert isinstance(rule["roleNames"], list) and len(rule["roleNames"]) > 0, \
        "rule.roleNames must be a non-empty array"
    assert rule["effect"] in ("ALLOW", "DENY"), \
        f"rule.effect must be ALLOW or DENY, got '{rule['effect']}'"
    assert isinstance(rule["priority"], int), \
        f"rule.priority must be an integer, got {type(rule['priority']).__name__}"
    assert isinstance(rule["enabled"], bool), \
        f"rule.enabled must be a boolean, got {type(rule['enabled']).__name__}"


def assert_rbac_rule_response(body):
    """Validate RbacRuleResponse: { rule: RbacRule }."""
    assert "rule" in body, "RbacRuleResponse must have 'rule' field"
    assert_rbac_rule_shape(body["rule"])


def assert_rbac_list_response(body):
    """Validate RbacRuleListResponse: { rules, limit, offset, total }."""
    assert_required_fields(body, ["rules", "limit", "offset", "total"])
    assert isinstance(body["rules"], list), "'rules' must be an array"
    assert isinstance(body["limit"], int), f"'limit' must be int, got {type(body['limit']).__name__}"
    assert isinstance(body["offset"], int), f"'offset' must be int, got {type(body['offset']).__name__}"
    assert isinstance(body["total"], int) and body["total"] >= 0, \
        "'total' must be a non-negative integer"
    for rule in body["rules"]:
        assert_rbac_rule_shape(rule)


# ── JBAC validators ────────────────────────────────────────────────────────

def assert_jbac_rule_shape(rule):
    """Validate required fields and types on a JbacRule object."""
    required = ["id", "name", "pathPattern", "methods", "enforcement"]
    for field in required:
        assert field in rule, f"JBAC rule missing required field '{field}'"
    assert isinstance(rule["id"], str) and rule["id"], \
        "rule.id must be a non-empty string"
    assert isinstance(rule["name"], str) and rule["name"], \
        "rule.name must be a non-empty string"
    assert isinstance(rule["pathPattern"], str) and rule["pathPattern"], \
        "rule.pathPattern must be a non-empty string"
    assert isinstance(rule["methods"], list) and len(rule["methods"]) > 0, \
        "rule.methods must be a non-empty array"
    assert isinstance(rule["enforcement"], str) and rule["enforcement"], \
        "rule.enforcement must be a non-empty string"


def assert_jbac_rule_response(body):
    """Validate JbacRuleResponse: { rule: JbacRule }."""
    assert "rule" in body, "JbacRuleResponse must have 'rule' field"
    assert_jbac_rule_shape(body["rule"])


def assert_jbac_list_response(body):
    """Validate JbacRuleListResponse: { rules, limit, offset, total }."""
    assert_required_fields(body, ["rules", "limit", "offset", "total"])
    assert isinstance(body["rules"], list), "'rules' must be an array"
    assert isinstance(body["limit"], int), f"'limit' must be int, got {type(body['limit']).__name__}"
    assert isinstance(body["offset"], int), f"'offset' must be int, got {type(body['offset']).__name__}"
    assert isinstance(body["total"], int) and body["total"] >= 0, \
        "'total' must be a non-negative integer"
    for rule in body["rules"]:
        assert_jbac_rule_shape(rule)


# ── Bulk and internal validators ───────────────────────────────────────────

def assert_bulk_create_response(body):
    """Validate BulkCreateRbacRulesResponse / BulkCreateJbacRulesResponse.

    Bulk insert is atomic — either all rules are created (201 with `created`
    count) or none are (4xx/5xx with Error array). There is no `failed` /
    `errors` field on a 201 response.
    """
    assert_required_fields(body, ["created"])
    assert isinstance(body["created"], int) and body["created"] >= 0, \
        "'created' must be a non-negative integer"


def assert_version_response(body):
    """Validate VersionResponse: { version (32-char string), timestamp (int64) }."""
    assert_required_fields(body, ["version", "timestamp"])
    assert isinstance(body["version"], str), \
        f"'version' must be a string, got {type(body['version']).__name__}"
    assert len(body["version"]) == 32, \
        f"'version' must be exactly 32 characters, got {len(body['version'])}"
    assert isinstance(body["timestamp"], int), \
        f"'timestamp' must be an integer, got {type(body['timestamp']).__name__}"


def assert_internal_rbac_response(body):
    """Validate InternalRbacRulesResponse: { rules, version }."""
    assert_required_fields(body, ["rules", "version"])
    assert isinstance(body["rules"], list), "'rules' must be an array"
    assert isinstance(body["version"], str), \
        f"'version' must be a string, got {type(body['version']).__name__}"
    assert len(body["version"]) == 32, \
        f"'version' must be exactly 32 characters, got {len(body['version'])}"
    for rule in body["rules"]:
        assert_rbac_rule_shape(rule)


def assert_internal_jbac_response(body):
    """Validate InternalJbacRulesResponse: { rules, version }."""
    assert_required_fields(body, ["rules", "version"])
    assert isinstance(body["rules"], list), "'rules' must be an array"
    assert isinstance(body["version"], str), \
        f"'version' must be a string, got {type(body['version']).__name__}"
    assert len(body["version"]) == 32, \
        f"'version' must be exactly 32 characters, got {len(body['version'])}"
    for rule in body["rules"]:
        assert_jbac_rule_shape(rule)
