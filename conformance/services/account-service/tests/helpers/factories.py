"""
Test data factories for the account service.

All factories produce unique data per call via uuid-based suffixes so tests
running in the same tenant do not collide. Tenant requests use the wire
envelope {"tenant": {...}}; tenant config requests use {"tenantConfig": {...}}.
"""

import uuid


def _uid():
    return uuid.uuid4().hex[:8].upper()


# ── Valid payloads ─────────────────────────────────────────────────────────

def make_tenant_request(**overrides):
    """Valid TenantRequest envelope: {"tenant": {...}}.

    Required fields: name, email. Optional: code (server derives from name
    if omitted), password, isActive (defaults to true), additionalAttributes.
    """
    uid = _uid()
    tenant = {
        "name": f"Conformance Tenant {uid}",
        "email": f"conformance-{uid.lower()}@example.com",
        "isActive": True,
    }
    tenant.update(overrides)
    return {"tenant": tenant}


def make_update_tenant_request(**overrides):
    """Valid TenantRequest envelope for update.

    Per the spec, the server only applies `isActive` and
    `additionalAttributes` on update; identifying fields (name, email, code)
    are ignored. We still send a complete tenant body to satisfy schema
    validation.
    """
    uid = _uid()
    tenant = {
        "name": f"Conformance Tenant Updated {uid}",
        "email": f"conformance-updated-{uid.lower()}@example.com",
        "isActive": False,
        "additionalAttributes": {"conformanceTest": True, "iteration": uid},
    }
    tenant.update(overrides)
    return {"tenant": tenant}


def make_tenant_config_request(tenant_code=None, **overrides):
    """Valid TenantConfigRequest envelope: {"tenantConfig": {...}}.

    tenant_code is the code of the parent tenant — the service ties configs
    to a tenant via this code. If omitted, no code is set on the config and
    POST /config will return 400 TENANT_NOT_FOUND.
    """
    uid = _uid()
    config = {
        "name": f"ConformanceConfig{uid}",
        "defaultLoginType": "password",
        "otpLength": "6",
        "enableUserBasedLogin": True,
        "languages": ["en"],
        "isActive": True,
    }
    if tenant_code:
        config["code"] = tenant_code
    config.update(overrides)
    return {"tenantConfig": config}


def make_update_tenant_config_request(tenant_code=None, documents=None, **overrides):
    """Valid TenantConfigRequest envelope for update.

    Per the spec, an update payload MUST re-supply every existing document on
    the config — omitting `documents` returns 400 DOCUMENTS_REQUIRED, and
    omitting an existing document id returns 400 MISSING_DOCUMENT. Pass the
    full list from the most recent read.
    """
    uid = _uid()
    config = {
        "name": f"ConformanceConfigUpdated{uid}",
        "defaultLoginType": "otp",
        "otpLength": "4",
        "enableUserBasedLogin": False,
        "languages": ["en", "hi"],
        "isActive": False,
    }
    if tenant_code:
        config["code"] = tenant_code
    if documents is not None:
        config["documents"] = documents
    config.update(overrides)
    return {"tenantConfig": config}


def make_signup_verify_request(reference_id, otp="123456"):
    """SignupVerifyRequest body. Requires a referenceId from a prior /signup."""
    return {"referenceId": reference_id, "otp": otp}


def make_signup_resend_request(reference_id):
    """SignupResendRequest body. Requires a referenceId from a prior /signup."""
    return {"referenceId": reference_id}


# ── Invalid Tenant payloads ────────────────────────────────────────────────

def make_invalid_tenant_request(strategy="missing_name"):
    """Returns an envelope with an intentionally invalid tenant body."""
    strategies = {
        "missing_name": {"email": f"test-{_uid().lower()}@example.com"},
        "missing_email": {"name": f"Test {_uid()}"},
        "empty_name": {"name": "", "email": f"test-{_uid().lower()}@example.com"},
        "empty_email": {"name": f"Test {_uid()}", "email": ""},
        "invalid_email_format": {"name": f"Test {_uid()}", "email": "not-an-email"},
        "name_too_long": {
            "name": "x" * 129,
            "email": f"test-{_uid().lower()}@example.com",
        },
        "email_too_short": {"name": f"Test {_uid()}", "email": "a@b"},
        "invalid_code": {
            "name": f"Test {_uid()}",
            "email": f"test-{_uid().lower()}@example.com",
            "code": "invalid lowercase!",  # must match ^[A-Z0-9]+$
        },
        "password_too_short": {
            "name": f"Test {_uid()}",
            "email": f"test-{_uid().lower()}@example.com",
            "password": "short",  # minLength: 8
        },
        "wrong_types": {
            "name": 12345,
            "email": True,
            "isActive": "yes",
        },
        "empty_tenant_envelope": None,  # caller wraps this; see below
    }
    inner = strategies.get(strategy, {})
    if inner is None:
        # caller wants {"tenant": {}} — empty envelope
        return {"tenant": {}}
    return {"tenant": inner}


def make_missing_tenant_envelope():
    """Returns a body missing the `tenant` envelope entirely."""
    return {"name": f"Test {_uid()}", "email": "test@example.com"}


# ── Invalid TenantConfig payloads ──────────────────────────────────────────

def make_invalid_tenant_config_request(strategy="missing_tenant_envelope"):
    strategies = {
        "missing_tenant_envelope": {},
        "invalid_code": {"tenantConfig": {"code": "lowercase!", "name": "Test"}},
        "name_too_long": {"tenantConfig": {"name": "x" * 129}},
        "name_empty": {"tenantConfig": {"name": ""}},
        "default_login_type_empty": {"tenantConfig": {"defaultLoginType": ""}},
        # otpLength must match enum ['4','6','8']
        "otp_length_not_in_enum": {"tenantConfig": {"otpLength": "5"}},
        "otp_length_letters": {"tenantConfig": {"otpLength": "six"}},
        # language items have minLength: 2 — single-char codes are invalid
        "language_code_too_short": {"tenantConfig": {"languages": ["e"]}},
        "language_code_too_long": {"tenantConfig": {"languages": ["x" * 17]}},
        "wrong_types": {
            "tenantConfig": {
                "name": 12345,
                "enableUserBasedLogin": "yes",
                "languages": "en",  # should be array
            }
        },
    }
    return strategies.get(strategy, {})


# ── Invalid Signup payloads ────────────────────────────────────────────────

def make_invalid_signup_verify_request(strategy="missing_reference_id"):
    strategies = {
        "missing_reference_id": {"otp": "123456"},
        "missing_otp": {"referenceId": str(uuid.uuid4())},
        "empty_reference_id": {"referenceId": "", "otp": "123456"},
        "empty_otp": {"referenceId": str(uuid.uuid4()), "otp": ""},
        "non_numeric_otp": {"referenceId": str(uuid.uuid4()), "otp": "abcdef"},
        "otp_too_long": {"referenceId": str(uuid.uuid4()), "otp": "123456789"},
        "reference_id_too_long": {"referenceId": "x" * 37, "otp": "123456"},
    }
    return strategies.get(strategy, {})


def make_invalid_signup_resend_request(strategy="missing_reference_id"):
    strategies = {
        "missing_reference_id": {},
        "empty_reference_id": {"referenceId": ""},
        "reference_id_too_long": {"referenceId": "x" * 37},
    }
    return strategies.get(strategy, {})
