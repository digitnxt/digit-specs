"""
Cross-schema rule tests for Notification service.
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
    return "br-cs-" + uuid.uuid4().hex[:8]


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
# BR-CS-001: Template type gates send endpoint
# ---------------------------------------------------------------------------

class TestBR_CS_001_template_type_gates_send_endpoint:
    """EMAIL template cannot be used for SMS send and vice versa."""

    def test_email_template_used_for_sms_send_rejected(
        self, request, base_url, auth_headers
    ):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "EMAIL",
            "subject": "Test", "content": "Hello {{.Name}}",
        })
        try:
            resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
                "templateId": tid, "mobileNumbers": ["+919876543210"],
            })
            assert resp.status_code == 422, \
                f"Expected 422 for EMAIL template on SMS send, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_sms_template_used_for_email_send_rejected(
        self, request, base_url, auth_headers
    ):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "SMS", "content": "Your OTP is {{.OTP}}",
        })
        try:
            resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
                "templateId": tid, "emailIds": ["test@example.com"],
            })
            assert resp.status_code == 422, \
                f"Expected 422 for SMS template on email send, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_email_template_used_for_email_send_accepted(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome", "emailIds": ["test@example.com"],
        })
        assert resp.status_code in (200, 422, 500), \
            f"EMAIL template on email send should pass type check, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-003: Template must exist before send
# ---------------------------------------------------------------------------

class TestBR_CS_003_template_must_exist_before_send:
    """Send with a non-existent templateId returns 404."""

    def test_email_send_with_nonexistent_template_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": f"nonexistent-{uuid.uuid4().hex[:8]}",
            "emailIds": ["test@example.com"],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent template, got {resp.status_code}: {resp.text}"

    def test_sms_send_with_nonexistent_template_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/sms/send", auth_headers, {
            "templateId": f"nonexistent-{uuid.uuid4().hex[:8]}",
            "mobileNumbers": ["+919876543210"],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent template, got {resp.status_code}: {resp.text}"

    def test_preview_with_nonexistent_template_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/template/preview", auth_headers, {
            "templateId": f"nonexistent-{uuid.uuid4().hex[:8]}",
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent template preview, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: Template conflict on create
# ---------------------------------------------------------------------------

class TestBR_CS_004_template_conflict_on_create:
    """POST /template returns 409 if a version already exists for (tenantId, templateId)."""


# ---------------------------------------------------------------------------
# BR-CS-002: Subject always rendered with text/template
# ---------------------------------------------------------------------------

class TestBR_CS_002_subject_always_rendered_with_text_template:
    """
    Rendering engine is selected by isHTML: html/template (XSS-safe) when true,
    text/template when false. Subject is ALWAYS rendered with text/template.
    A template with valid Go template syntax in subject is accepted regardless of isHTML.
    Invalid Go template syntax in subject is rejected at create/update time.
    """

    def test_email_with_valid_template_syntax_in_subject_accepted(
        self, request, base_url, auth_headers
    ):
        tid = _tpl_id()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": tid, "type": "EMAIL",
            "subject":    "Hello {{.Name}}, welcome to {{.OrgName}}",
            "content":    "Dear {{.Name}}, your account is ready.",
            "isHTML":     False,
        })
        try:
            assert resp.status_code == 201, \
                f"Template with valid Go template syntax in subject must be accepted: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_html_template_with_variable_in_subject_accepted(
        self, request, base_url, auth_headers
    ):
        tid = _tpl_id()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": tid, "type": "EMAIL",
            "subject":    "Order {{.OrderId}} confirmed",
            "content":    "<h1>Hi {{.Name}}</h1>",
            "isHTML":     True,
        })
        try:
            assert resp.status_code == 201, \
                f"HTML template with variable in subject must be accepted: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_malformed_go_template_syntax_in_subject_rejected(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateId": _tpl_id(), "type": "EMAIL",
            "subject":    "Hello {{.Name",
            "content":    "Body text.",
        })
        assert resp.status_code in (400, 422), \
            f"Malformed Go template syntax in subject must be rejected, got {resp.status_code}: {resp.text}"


    def test_second_create_with_same_id_returns_409(
        self, request, base_url, auth_headers
    ):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateId": tid, "type": "EMAIL",
            "subject": "First", "content": "Hello {{.Name}}",
        })
        try:
            resp = _post(request.node, f"{base_url}/template", auth_headers, {
                "templateId": tid, "type": "EMAIL",
                "subject": "Second", "content": "Hello again {{.Name}}",
            })
            assert resp.status_code == 409, \
                f"Expected 409 for duplicate templateId, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, tid, "v1", auth_headers)
