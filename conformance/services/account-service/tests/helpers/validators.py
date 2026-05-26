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


# ── Tenant validators ──────────────────────────────────────────────────────

def assert_tenant_shape(tenant):
    """Validate required fields and types on a Tenant object."""
    required = ["id", "name", "email"]
    for field in required:
        assert field in tenant, f"Tenant missing required field '{field}'"
    assert isinstance(tenant["id"], str) and tenant["id"], \
        "tenant.id must be a non-empty string"
    assert isinstance(tenant["name"], str) and tenant["name"], \
        "tenant.name must be a non-empty string"
    assert isinstance(tenant["email"], str) and "@" in tenant["email"], \
        f"tenant.email must contain '@', got '{tenant['email']}'"
    # Optional but typed fields
    if "isActive" in tenant:
        assert isinstance(tenant["isActive"], bool), \
            f"tenant.isActive must be boolean, got {type(tenant['isActive']).__name__}"
    if "code" in tenant:
        assert isinstance(tenant["code"], str), \
            f"tenant.code must be a string, got {type(tenant['code']).__name__}"


def assert_tenant_response(body):
    """Validate TenantResponse envelope: {"tenants": [Tenant, ...]}.

    The service always returns an array even for single-record create/update.
    """
    assert "tenants" in body, "TenantResponse must have 'tenants' envelope"
    assert isinstance(body["tenants"], list), \
        f"'tenants' must be a list, got {type(body['tenants']).__name__}"
    for tenant in body["tenants"]:
        assert_tenant_shape(tenant)


def assert_tenant_response_nonempty(body):
    """Validate TenantResponse envelope with at least one tenant."""
    assert_tenant_response(body)
    assert len(body["tenants"]) > 0, \
        "Expected at least one tenant in response, got empty array"


# ── TenantConfig validators ────────────────────────────────────────────────

def assert_tenant_config_shape(config):
    """Validate basic shape of a TenantConfig object.

    TenantConfig has NO strict required fields per the spec — all properties
    are optional. We only assert types when fields are present.
    """
    if "id" in config:
        assert isinstance(config["id"], str), \
            f"tenantConfig.id must be a string, got {type(config['id']).__name__}"
    if "name" in config:
        assert isinstance(config["name"], str), \
            f"tenantConfig.name must be a string, got {type(config['name']).__name__}"
    if "code" in config:
        assert isinstance(config["code"], str), \
            f"tenantConfig.code must be a string, got {type(config['code']).__name__}"
    if "isActive" in config:
        assert isinstance(config["isActive"], bool), \
            f"tenantConfig.isActive must be boolean, got {type(config['isActive']).__name__}"
    if "languages" in config:
        assert isinstance(config["languages"], list), \
            "tenantConfig.languages must be a list"
    if "documents" in config:
        assert isinstance(config["documents"], list), \
            "tenantConfig.documents must be a list"


def assert_tenant_config_response(body):
    """Validate TenantConfigResponse envelope: {"tenantConfigs": [...]}."""
    assert "tenantConfigs" in body, "Response must have 'tenantConfigs' envelope"
    assert isinstance(body["tenantConfigs"], list), \
        f"'tenantConfigs' must be a list, got {type(body['tenantConfigs']).__name__}"
    for config in body["tenantConfigs"]:
        assert_tenant_config_shape(config)


def assert_tenant_config_response_nonempty(body):
    assert_tenant_config_response(body)
    assert len(body["tenantConfigs"]) > 0, \
        "Expected at least one tenantConfig in response, got empty array"


# ── Signup validators ──────────────────────────────────────────────────────

def assert_signup_initiate_response(body):
    """Validate SignupInitiateResponse: {referenceId, expiresIn, destination, message}."""
    assert_required_fields(body, ["referenceId", "expiresIn", "destination", "message"])
    assert isinstance(body["referenceId"], str) and body["referenceId"], \
        "referenceId must be a non-empty string"
    assert isinstance(body["expiresIn"], int) and body["expiresIn"] > 0, \
        f"expiresIn must be a positive integer, got {body['expiresIn']!r}"
    assert isinstance(body["destination"], str) and "@" in body["destination"], \
        f"destination must be an email string with '@', got {body['destination']!r}"
    assert isinstance(body["message"], str) and body["message"], \
        f"message must be a non-empty string, got {body['message']!r}"


def assert_signup_resend_response(body):
    """Validate SignupResendResponse:
       {referenceId, resendCount, resendAllowedAfter, expiresAt, message}.

    Note: resendAllowedAfter and expiresAt are ISO-8601 date-time strings,
    not integer seconds (the old `cooldownSeconds` / `expiresIn` fields were
    replaced with timestamps).
    """
    assert_required_fields(
        body,
        ["referenceId", "resendCount", "resendAllowedAfter", "expiresAt", "message"],
    )
    assert isinstance(body["referenceId"], str) and body["referenceId"], \
        "referenceId must be a non-empty string"
    assert isinstance(body["resendCount"], int) and body["resendCount"] >= 0, \
        f"resendCount must be a non-negative integer, got {body['resendCount']!r}"
    assert isinstance(body["resendAllowedAfter"], str) and "T" in body["resendAllowedAfter"], \
        f"resendAllowedAfter must be an ISO-8601 date-time string, got {body['resendAllowedAfter']!r}"
    assert isinstance(body["expiresAt"], str) and "T" in body["expiresAt"], \
        f"expiresAt must be an ISO-8601 date-time string, got {body['expiresAt']!r}"
    assert isinstance(body["message"], str) and body["message"], \
        f"message must be a non-empty string, got {body['message']!r}"
