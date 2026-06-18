import uuid


def _tpl_id():
    """Generate a unique templateId (1-128 chars, URL-safe)."""
    return "notif-" + uuid.uuid4().hex[:10]


# ---------------------------------------------------------------------------
# Template request factories
# ---------------------------------------------------------------------------

def make_email_template(template_id=None, **overrides):
    """Minimal valid TemplateRequest for an EMAIL template."""
    base = {
        "templateId": template_id or _tpl_id(),
        "type":       "EMAIL",
        "subject":    "Hello from DIGIT",
        "content":    "Dear user, your request has been processed.",
        "isHTML":     False,
    }
    return {**base, **overrides}


def make_html_email_template(template_id=None):
    """EMAIL template with HTML content and a template variable."""
    return {
        "templateId": template_id or _tpl_id(),
        "type":       "EMAIL",
        "subject":    "Welcome to {{ .orgName }}",
        "content":    "<h1>Hello {{ .name }}</h1><p>Your account is ready.</p>",
        "isHTML":     True,
    }


def make_sms_template(template_id=None, **overrides):
    """Minimal valid TemplateRequest for an SMS template."""
    base = {
        "templateId": template_id or _tpl_id(),
        "type":       "SMS",
        "content":    "Your OTP is {{ .otp }}. Valid for 10 minutes.",
    }
    return {**base, **overrides}


def make_template_update(template_id, **overrides):
    """
    Updated TemplateRequest for PUT — increments to v2.
    templateId is immutable; only subject/content/isHTML may change.
    """
    base = {
        "templateId": template_id,
        "type":       "EMAIL",
        "subject":    "Updated: Hello from DIGIT",
        "content":    "Dear {{ .name }}, your request {{ .requestId }} has been processed.",
        "isHTML":     False,
    }
    return {**base, **overrides}


def make_invalid_template(strategy="missing_required"):
    """Invalid TemplateRequest payloads for negative tests."""
    strategies = {
        "missing_required":    {},
        "missing_template_id": {"type": "EMAIL", "content": "Hello"},
        "missing_type":        {"templateId": _tpl_id(), "content": "Hello"},
        "missing_content":     {"templateId": _tpl_id(), "type": "EMAIL"},
        "invalid_type":        {"templateId": _tpl_id(), "type": "PUSH", "content": "Hi"},
        "template_id_too_long": {
            "templateId": "x" * 129,
            "type":       "EMAIL",
            "content":    "Hello",
        },
        "content_empty": {
            "templateId": _tpl_id(),
            "type":       "EMAIL",
            "content":    "",
        },
    }
    return strategies.get(strategy, {})


# ---------------------------------------------------------------------------
# Preview request factories
# ---------------------------------------------------------------------------

def make_preview_request(template_id, version=None, payload=None):
    """Valid TemplatePreviewRequest."""
    req = {"templateId": template_id}
    if version:
        req["version"] = version
    if payload:
        req["payload"] = payload
    return req


def make_invalid_preview_request(strategy="missing_required"):
    """Invalid TemplatePreviewRequest payloads for negative tests."""
    strategies = {
        "missing_required":    {},
        "empty_template_id":   {"templateId": ""},
        "template_id_too_long": {"templateId": "x" * 129},
        "invalid_version":     {"templateId": _tpl_id(), "version": "latest"},
    }
    return strategies.get(strategy, {})


# ---------------------------------------------------------------------------
# Email send request factories
# ---------------------------------------------------------------------------

def make_email_request(template_id, emails=None, version=None, payload=None):
    """Valid EmailRequest."""
    req = {
        "templateId": template_id,
        "emailIds":   emails or ["conformance-test@example.com"],
    }
    if version:
        req["version"] = version
    if payload:
        req["payload"] = payload
    return req


def make_invalid_email_request(strategy="missing_required"):
    """Invalid EmailRequest payloads for negative tests."""
    strategies = {
        "missing_required":     {},
        "missing_template_id":  {"emailIds": ["test@example.com"]},
        "missing_email_ids":    {"templateId": _tpl_id()},
        "empty_email_ids":      {"templateId": _tpl_id(), "emailIds": []},
        "invalid_email_format": {"templateId": _tpl_id(), "emailIds": ["not-an-email"]},
        "too_many_emails":      {
            "templateId": _tpl_id(),
            "emailIds":   [f"user{i}@example.com" for i in range(51)],
        },
        "invalid_version": {
            "templateId": _tpl_id(),
            "emailIds":   ["test@example.com"],
            "version":    "latest",
        },
    }
    return strategies.get(strategy, {})


# ---------------------------------------------------------------------------
# SMS send request factories
# ---------------------------------------------------------------------------

def make_sms_request(template_id, mobile_numbers=None, version=None, payload=None,
                     category=None):
    """Valid SMSRequest."""
    req = {
        "templateId":    template_id,
        "mobileNumbers": mobile_numbers or ["+919876543210"],
    }
    if version:
        req["version"] = version
    if payload:
        req["payload"] = payload
    if category:
        req["category"] = category
    return req


def make_invalid_sms_request(strategy="missing_required"):
    """Invalid SMSRequest payloads for negative tests."""
    strategies = {
        "missing_required":        {},
        "missing_template_id":     {"mobileNumbers": ["+919876543210"]},
        "missing_mobile_numbers":  {"templateId": _tpl_id()},
        "empty_mobile_numbers":    {"templateId": _tpl_id(), "mobileNumbers": []},
        "invalid_mobile_format":   {"templateId": _tpl_id(), "mobileNumbers": ["9876543210"]},
        "too_many_mobiles":        {
            "templateId":    _tpl_id(),
            "mobileNumbers": [f"+9100000000{i:02d}" for i in range(11)],
        },
        "invalid_category": {
            "templateId":    _tpl_id(),
            "mobileNumbers": ["+919876543210"],
            "category":      "INVALID_CATEGORY",
        },
        "invalid_version": {
            "templateId":    _tpl_id(),
            "mobileNumbers": ["+919876543210"],
            "version":       "latest",
        },
    }
    return strategies.get(strategy, {})
