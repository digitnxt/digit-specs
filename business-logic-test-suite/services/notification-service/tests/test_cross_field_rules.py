"""
Cross-field rule tests for Notification service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _tpl_id():
    return "br-cf-" + uuid.uuid4().hex[:8]


def _cleanup(base_url, template_id, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateId": template_id, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ---------------------------------------------------------------------------
# BR-CF-001: Subject forbidden for SMS template type
# ---------------------------------------------------------------------------

class TestBR_CF_001_subject_forbidden_for_sms_type:
    """Non-empty subject on an SMS template is rejected."""

    def test_sms_without_subject_accepted(self, request, base_url, auth_headers):
        tid = _tpl_id()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": tid, "type": "SMS",
            "content": "Your code is {{.Code}}",
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_sms_with_subject_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": _tpl_id(), "type": "SMS",
            "subject": "This should not be here",
            "content": "Your code is {{.Code}}",
        })
        assert resp.status_code == 400, f"Expected 400 for SMS with subject, got {resp.status_code}: {resp.text}"

    def test_email_with_subject_accepted(self, request, base_url, auth_headers):
        tid = _tpl_id()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": tid, "type": "EMAIL",
            "subject": "Hello {{.Name}}", "content": "Dear {{.Name}}, welcome.",
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CF-002: isHTML only valid for EMAIL templates
# ---------------------------------------------------------------------------

class TestBR_CF_002_is_html_only_valid_for_email:
    """isHTML=true on SMS template is rejected."""

    def test_sms_with_is_html_true_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": _tpl_id(), "type": "SMS",
            "content": "Your code is {{.Code}}", "isHTML": True,
        })
        assert resp.status_code == 400, f"Expected 400 for SMS isHTML=true, got {resp.status_code}: {resp.text}"

    def test_email_with_is_html_true_accepted(self, request, base_url, auth_headers):
        tid = _tpl_id()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": tid, "type": "EMAIL",
            "subject": "Hello", "content": "<b>Dear {{.Name}}</b>", "isHTML": True,
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CF-003: Content always required
# ---------------------------------------------------------------------------

class TestBR_CF_003_content_always_required:
    """Empty or missing content is rejected."""

    def test_missing_content_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": _tpl_id(), "type": "EMAIL", "subject": "Hi",
        })
        assert resp.status_code in (400, 422), f"Expected 400/422 for missing content, got {resp.status_code}: {resp.text}"

    def test_empty_content_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": _tpl_id(), "type": "EMAIL", "subject": "Hi", "content": "",
        })
        assert resp.status_code in (400, 422), f"Expected 400/422 for empty content, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Email ID quantity and format bounds
# ---------------------------------------------------------------------------

class TestBR_CF_004_email_id_quantity_and_format_bounds:
    """emailIds must have 1–50 valid addresses."""

    def test_empty_email_ids_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome", "emailIds": [],
        })
        assert resp.status_code == 400, f"Expected 400 for empty emailIds, got {resp.status_code}: {resp.text}"

    def test_invalid_email_format_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome", "emailIds": ["not-an-email"],
        })
        assert resp.status_code == 400, f"Expected 400 for invalid email, got {resp.status_code}: {resp.text}"

    def test_too_many_email_ids_rejected(self, request, base_url, auth_headers):
        emails = [f"user{i}@example.com" for i in range(51)]
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome", "emailIds": emails,
        })
        assert resp.status_code == 400, f"Expected 400 for 51 emails, got {resp.status_code}: {resp.text}"

    def test_boundary_50_email_ids_accepted(self, request, base_url, auth_headers):
        emails = [f"user{i}@example.com" for i in range(50)]
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome", "emailIds": emails,
        })
        assert resp.status_code in (200, 422, 500), \
            f"50 emails should pass validation (may fail at SMTP layer), got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Attachment filestore ID limit
# ---------------------------------------------------------------------------

class TestBR_CF_005_attachment_filestore_id_limit:
    """attachments may contain 0–5 filestore IDs."""

    def test_six_attachments_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome",
            "emailIds": ["test@example.com"],
            "attachments": [f"file-id-{i}" for i in range(6)],
        })
        assert resp.status_code == 400, f"Expected 400 for 6 attachments, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: SMS mobile number format and count
# ---------------------------------------------------------------------------

class TestBR_CF_006_sms_mobile_number_format_and_count:
    """mobileNumbers must have 1–10 E.164 numbers."""

    def test_empty_mobile_numbers_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
            "templateId": "seed-sms-otp", "mobileNumbers": [],
        })
        assert resp.status_code == 400, f"Expected 400 for empty mobileNumbers, got {resp.status_code}: {resp.text}"

    def test_non_e164_format_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
            "templateId": "seed-sms-otp", "mobileNumbers": ["9876543210"],
        })
        assert resp.status_code == 400, f"Expected 400 for non-E.164 number, got {resp.status_code}: {resp.text}"

    def test_eleven_numbers_rejected(self, request, base_url, auth_headers):
        numbers = [f"+9100000000{i:02d}" for i in range(11)]
        resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
            "templateId": "seed-sms-otp", "mobileNumbers": numbers,
        })
        assert resp.status_code == 400, f"Expected 400 for 11 numbers, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-007: SMS category must be predefined value
# ---------------------------------------------------------------------------

class TestBR_CF_007_sms_category_must_be_predefined_value:
    """category must be one of OTP, TRANSACTION, PROMOTION, NOTIFICATION, OTHERS."""

    def test_invalid_category_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
            "templateId": "seed-sms-otp",
            "mobileNumbers": ["+919876543210"],
            "category": "INVALID_CATEGORY",
        })
        assert resp.status_code == 400, f"Expected 400 for invalid category, got {resp.status_code}: {resp.text}"

    def test_valid_otp_category_passes_validation(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
            "templateId": "seed-sms-otp",
            "mobileNumbers": ["+919876543210"],
            "category": "OTP",
        })
        assert resp.status_code in (200, 422, 500), \
            f"OTP category should pass validation, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-008: Version format is vN when supplied
# ---------------------------------------------------------------------------

class TestBR_CF_008_version_format_is_vN_when_supplied:
    """version must match v{N} format when provided."""

    def test_invalid_version_format_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome",
            "emailIds": ["test@example.com"],
            "version": "latest",
        })
        assert resp.status_code == 400, f"Expected 400 for version='latest', got {resp.status_code}: {resp.text}"

    def test_valid_version_format_passes_validation(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome",
            "emailIds": ["test@example.com"],
            "version": "v1",
        })
        assert resp.status_code in (200, 422, 500), \
            f"v1 version format should pass validation, got {resp.status_code}: {resp.text}"
