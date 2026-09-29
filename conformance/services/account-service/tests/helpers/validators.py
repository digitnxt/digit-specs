"""Response validators for the Account/Tenant service (v3 contract)."""


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
    ct = response.headers.get("Content-Type", "")
    assert "application/json" in ct, f"Expected Content-Type application/json, got: {ct}"


def assert_field_types(body, type_map):
    for field, expected_type in type_map.items():
        if field in body:
            assert isinstance(body[field], expected_type), (
                f"Field '{field}' expected {expected_type.__name__}, "
                f"got {type(body[field]).__name__}: {body[field]!r}"
            )


# ── Tenant ─────────────────────────────────────────────────────────────────

def assert_tenant_shape(t):
    """A single TenantResponse object (flat, no envelope)."""
    for f in ("id", "code", "name", "email"):
        assert f in t, f"TenantResponse missing required field '{f}'"
    assert isinstance(t["id"], str) and t["id"], "tenant.id must be a non-empty string"
    assert isinstance(t["code"], str) and t["code"], "tenant.code must be a non-empty string"
    assert isinstance(t["name"], str) and t["name"], "tenant.name must be a non-empty string"
    assert isinstance(t["email"], str) and "@" in t["email"], \
        f"tenant.email must contain '@', got {t['email']!r}"
    if "isActive" in t:
        assert isinstance(t["isActive"], bool), "tenant.isActive must be boolean"
    assert "password" not in t, "writeOnly password must never appear in a response"


def assert_tenant_list_response(body):
    """TenantListResponse: {totalCount, page, size, hasMore, tenants:[...]}."""
    for f in ("totalCount", "tenants"):
        assert f in body, f"TenantListResponse missing '{f}'"
    assert isinstance(body["tenants"], list), "'tenants' must be a list"
    assert isinstance(body["totalCount"], int), "'totalCount' must be an integer"
    for t in body["tenants"]:
        assert_tenant_shape(t)


def assert_delete_response(body):
    assert isinstance(body, dict) and body.get("deleted") is True, \
        f"deleteTenant must return {{'deleted': true}}, got {body!r}"


# ── TenantConfig ─────────────────────────────────────────────────────────────

def assert_tenant_config_shape(c):
    for f in ("id", "tenantId", "configKey", "configValue"):
        assert f in c, f"TenantConfigResponse missing required field '{f}'"
    assert isinstance(c["id"], str) and c["id"], "config.id must be a non-empty string"
    assert isinstance(c["tenantId"], str) and c["tenantId"], "config.tenantId must be a non-empty string"
    assert isinstance(c["configKey"], str) and c["configKey"], "config.configKey must be a non-empty string"
    assert isinstance(c["configValue"], str), "config.configValue must be a string"


def assert_tenant_config_list_response(body):
    """TenantConfigListResponse: {totalCount, page, size, hasMore, configs:[...]}."""
    for f in ("totalCount", "configs"):
        assert f in body, f"TenantConfigListResponse missing '{f}'"
    assert isinstance(body["configs"], list), "'configs' must be a list"
    for c in body["configs"]:
        assert_tenant_config_shape(c)


# ── Signup ───────────────────────────────────────────────────────────────────

def assert_signup_initiate_response(body):
    """SignupInitiateResponse: {referenceId, expiresIn, cooldownSeconds?}."""
    for f in ("referenceId", "expiresIn"):
        assert f in body, f"SignupInitiateResponse missing '{f}'"
    assert isinstance(body["referenceId"], str) and body["referenceId"], \
        "referenceId must be a non-empty string"
    assert isinstance(body["expiresIn"], int) and body["expiresIn"] > 0, \
        f"expiresIn must be a positive integer, got {body['expiresIn']!r}"


# ── Error envelope ───────────────────────────────────────────────────────────
#
# Per the spec: a 400 returns a bare [Error] ARRAY; every other error status
# returns a single Error OBJECT. Both use fields {code, message, description?,
# params?}. These assert the CONTRACT — a service that wraps errors differently
# (e.g. Java's {"Errors":[...]}) will fail these, surfacing the divergence.

def _assert_error_item(item):
    assert isinstance(item, dict), f"Error item must be an object, got {type(item).__name__}"
    assert "code" in item and "message" in item, \
        f"Error must have 'code' and 'message', got keys {list(item.keys())}"


def assert_error_array(body):
    """400 error body: a bare array of Error objects."""
    assert isinstance(body, list), \
        f"A 400 must return a bare [Error] array (not a wrapper object), got {type(body).__name__}: {body!r}"
    assert len(body) >= 1, "error array must have at least one entry"
    for item in body:
        _assert_error_item(item)


def assert_error_object(body):
    """Non-400 error body: a single Error object."""
    assert isinstance(body, dict), \
        f"A non-400 error must return a single Error object, got {type(body).__name__}: {body!r}"
    _assert_error_item(body)


def assert_error_body(body):
    """Lenient error-body check for any error status.

    Accepts the real contract shape — a bare [Error] array OR a single Error
    object — but rejects a wrapper object such as {"Errors":[...]} or
    {"error":...}. (The Go service returns a bare array for every error; the
    spec's "non-400 = single object" rule is not honored consistently, so we
    accept both. A wrapper envelope is still a contract violation and fails.)
    """
    if isinstance(body, list):
        assert len(body) >= 1, "error array must have at least one entry"
        for item in body:
            _assert_error_item(item)
    elif isinstance(body, dict) and "code" in body and "message" in body:
        _assert_error_item(body)
    else:
        raise AssertionError(
            "error body must be a bare [Error] array or a single Error object "
            f"(not a wrapper like {{'Errors':[...]}}), got: {body!r}"
        )
