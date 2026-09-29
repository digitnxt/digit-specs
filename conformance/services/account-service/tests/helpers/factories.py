"""
Test data factories for the Account/Tenant service (v3 contract).

Bodies are FLAT (no {"tenant": ...} envelope). Tenant `code` is server-derived
from `name` (uppercased, spaces stripped) and is globally unique, so every
factory uses a random suffix to avoid 409/DUPLICATE collisions across runs.
"""

import uuid


def _uid():
    return uuid.uuid4().hex[:8]


# ── Valid payloads ─────────────────────────────────────────────────────────

def make_tenant_request(**overrides):
    """Valid TenantCreateRequest (flat). Required: name, email."""
    uid = _uid()
    body = {
        "name": f"Conf Tenant {uid}",
        "email": f"conf-{uid}@example.com",
    }
    body.update(overrides)
    return body


def make_update_tenant_request(**overrides):
    """Valid TenantUpdateRequest (flat, partial update).

    Only mutable fields are applied; `additionalAttributes` is replaced in full.
    """
    body = {
        "additionalAttributes": {"conformanceTest": "true", "iteration": _uid()},
    }
    body.update(overrides)
    return body


def make_signup_initiate_request(**overrides):
    """SignupInitiateRequest — same required fields as create (name, email)."""
    uid = _uid()
    body = {
        "name": f"Conf Signup {uid}",
        "email": f"conf-signup-{uid}@example.com",
    }
    body.update(overrides)
    return body


def make_signup_verify_request(reference_id, otp="123456", purpose="registration"):
    """SignupVerifyRequest. Requires a referenceId from a prior initiate."""
    return {"referenceId": reference_id, "otp": otp, "purpose": purpose}


def make_signup_resend_request(reference_id):
    """ResendOTPRequest. Requires a referenceId from a prior initiate."""
    return {"referenceId": reference_id}


def make_tenant_config_request(tenant_id, **overrides):
    """Valid TenantConfigCreateRequest (flat). Required: tenantId, configKey, configValue.

    tenant_id must be the UUID of an existing tenant; it must also match the
    X-Tenant-Id header the caller sends.
    """
    body = {
        "tenantId": tenant_id,
        "configKey": f"CONF_TEST_KEY_{_uid().upper()}",
        "configValue": "conformance-value",
        "description": "Conformance test config",
    }
    body.update(overrides)
    return body


def make_update_tenant_config_request(**overrides):
    """TenantConfigUpdateRequest (partial).

    The spec marks all fields optional, but the Go service requires `configKey`
    on update, so we always include it to obtain a 200.
    """
    body = {
        "configKey": f"CONF_TEST_KEY_{_uid().upper()}",
        "configValue": "updated-value",
        "description": "Updated conformance config",
    }
    body.update(overrides)
    return body


# ── Invalid Tenant payloads ────────────────────────────────────────────────

def make_invalid_tenant_request(strategy="missing_name"):
    strategies = {
        "missing_name": {"email": f"t-{_uid()}@example.com"},
        "missing_email": {"name": f"Test {_uid()}"},
        "empty_name": {"name": "", "email": f"t-{_uid()}@example.com"},
        "empty_email": {"name": f"Test {_uid()}", "email": ""},
        "invalid_email_format": {"name": f"Test {_uid()}", "email": "not-an-email"},
        "name_too_long": {"name": "x" * 257, "email": f"t-{_uid()}@example.com"},  # max 256
        "email_too_short": {"name": f"Test {_uid()}", "email": "a@b"},              # min 5
        "password_too_short": {
            "name": f"Test {_uid()}", "email": f"t-{_uid()}@example.com",
            "password": "short",  # min 8
        },
        "invalid_phone": {
            "name": f"Test {_uid()}", "email": f"t-{_uid()}@example.com",
            "phone": "12345",  # must match ^\+[1-9]\d{6,14}$
        },
        "wrong_types": {"name": 12345, "email": True},
        "empty_body": {},
    }
    return strategies.get(strategy, {})


# ── Invalid TenantConfig payloads ──────────────────────────────────────────

def make_invalid_tenant_config_request(tenant_id, strategy="missing_config_key"):
    strategies = {
        "missing_tenant_id": {"configKey": "K", "configValue": "v"},
        "missing_config_key": {"tenantId": tenant_id, "configValue": "v"},
        "missing_config_value": {"tenantId": tenant_id, "configKey": "K"},
        "empty_config_key": {"tenantId": tenant_id, "configKey": "", "configValue": "v"},
        "empty_config_value": {"tenantId": tenant_id, "configKey": "K", "configValue": ""},
        "bad_tenant_id_format": {"tenantId": "not-a-uuid", "configKey": "K", "configValue": "v"},
        "wrong_types": {"tenantId": tenant_id, "configKey": 123, "configValue": True},
        "empty_body": {},
    }
    return strategies.get(strategy, {})


# ── Invalid Signup payloads ────────────────────────────────────────────────

def make_invalid_signup_verify_request(strategy="missing_reference_id"):
    ref = "r" * 20  # satisfies minLength 10
    strategies = {
        "missing_reference_id": {"otp": "123456", "purpose": "registration"},
        "missing_otp": {"referenceId": ref, "purpose": "registration"},
        "missing_purpose": {"referenceId": ref, "otp": "123456"},
        "empty_reference_id": {"referenceId": "", "otp": "123456", "purpose": "registration"},
        "short_reference_id": {"referenceId": "abc", "otp": "123456", "purpose": "registration"},  # min 10
        "non_numeric_otp": {"referenceId": ref, "otp": "abcdef", "purpose": "registration"},
        "otp_too_short": {"referenceId": ref, "otp": "123", "purpose": "registration"},   # min 4
        "otp_too_long": {"referenceId": ref, "otp": "123456789", "purpose": "registration"},  # max 8
        "bad_purpose": {"referenceId": ref, "otp": "123456", "purpose": "login"},  # enum [registration]
    }
    return strategies.get(strategy, {})


def make_invalid_signup_resend_request(strategy="missing_reference_id"):
    strategies = {
        "missing_reference_id": {},
        "empty_reference_id": {"referenceId": ""},
        "short_reference_id": {"referenceId": "abc"},  # min 10
    }
    return strategies.get(strategy, {})
