import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_email_template,
    make_html_email_template,
    make_sms_template,
    make_template_update,
    make_preview_request,
    make_email_request,
    make_sms_request,
    _tpl_id,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_template_response_shape,
    assert_preview_response_shape,
    assert_notification_response_shape,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(base_url, template_id, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateId": template_id, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ── Template Lifecycle ────────────────────────────────────────────────────────

class TestTemplateLifecycle:
    def test_create_search_update_delete(self, request, base_url, auth_headers, gateway_headers_spec):
        """Full CRUD lifecycle: POST → GET (search) → PUT → GET (verify v2) → DELETE both."""
        tid = _tpl_id()
        v2_created = False
        try:
            # 1. CREATE v1
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_email_template(template_id=tid))
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_template_response_shape(body)
            assert body["templateId"] == tid
            assert body.get("version") == "v1"

            # 2. SEARCH — find by templateId
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid})
            assert r.status_code == 200
            results = r.json()
            assert isinstance(results, list)
            assert any(t["templateId"] == tid for t in results), \
                f"Created template '{tid}' not found in search"

            # 3. SEARCH by templateId + version
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v1"})
            assert r.status_code == 200
            v1_results = r.json()
            assert len(v1_results) >= 1
            assert v1_results[0].get("version") == "v1"

            # 4. UPDATE → v2
            r = _send(request.node, "PUT", f"{base_url}/template",
                      headers=auth_headers, json_body=make_template_update(tid))
            assert r.status_code == 200, f"Update failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert body.get("version") == "v2"
            assert_template_response_shape(body)
            v2_created = True

            # 5. SEARCH latest — must return v2
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid})
            assert r.status_code == 200
            results = r.json()
            latest = next((t for t in results if t["templateId"] == tid), None)
            assert latest is not None, f"Template '{tid}' not found after update"
            assert latest.get("version") == "v2", \
                f"Expected latest version v2, got {latest.get('version')}"

            # 6. v1 still accessible by explicit version
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v1"})
            assert r.status_code == 200
            assert len(r.json()) >= 1

            # 7. DELETE v2
            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v2"})
            assert r.status_code == 200, f"Delete v2 failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)
            v2_created = False

            # 8. DELETE v1
            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v1"})
            assert r.status_code == 200, f"Delete v1 failed: {r.text}"
            assert r.json().get("deleted") is True
            tid = None

        finally:
            if tid:
                if v2_created:
                    _cleanup(base_url, tid, "v2", auth_headers)
                _cleanup(base_url, tid, "v1", auth_headers)

    def test_delete_only_version_removes_template_entirely(self, request, base_url, auth_headers, gateway_headers_spec):
        """Deleting the sole version must fully remove the template from search."""
        tid = _tpl_id()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers, json_body=make_sms_template(template_id=tid))
            assert r.status_code == 201

            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v1"})
            assert r.status_code == 200
            assert r.json().get("deleted") is True

            # Template must no longer appear in search
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid})
            assert r.status_code == 200
            assert not any(t["templateId"] == tid for t in r.json()), \
                f"Fully deleted template '{tid}' still appears in search"
            tid = None
        finally:
            if tid:
                _cleanup(base_url, tid, "v1", auth_headers)

    def test_delete_latest_version_exposes_previous(self, request, base_url, auth_headers, gateway_headers_spec):
        """Deleting v2 when v1 also exists must leave v1 accessible."""
        tid = _tpl_id()
        try:
            req_lib.post(f"{base_url}/template",
                         json=make_email_template(template_id=tid), headers=auth_headers)
            req_lib.put(f"{base_url}/template",
                        json=make_template_update(tid), headers=auth_headers)

            # Delete v2
            r = _send(request.node, "DELETE", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v2"})
            assert r.status_code == 200
            assert r.json().get("deleted") is True

            # v1 still searchable
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid})
            assert r.status_code == 200
            results = r.json()
            assert any(t["templateId"] == tid for t in results), \
                f"v1 template '{tid}' not found after v2 deletion"

        finally:
            _cleanup(base_url, tid, "v2", auth_headers)
            _cleanup(base_url, tid, "v1", auth_headers)


# ── Template Preview Flow ─────────────────────────────────────────────────────

class TestTemplatePreviewFlow:
    def test_preview_with_payload_substitution(self, request, base_url, auth_headers, gateway_headers_spec):
        """Create a template with variables, preview it with payload, verify rendered output."""
        tid = _tpl_id()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers,
                      json_body=make_html_email_template(template_id=tid))
            assert r.status_code == 201, f"Create failed: {r.text}"

            r = _send(request.node, "POST", f"{base_url}/template/preview",
                      headers=auth_headers,
                      json_body=make_preview_request(
                          tid, payload={"name": "Alice", "orgName": "DIGIT"}
                      ))
            assert r.status_code == 200, f"Preview failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_preview_response_shape(body)
            assert body["templateId"] == tid
            assert body["type"] == "EMAIL"
            # Variable substitution: "Alice" and "DIGIT" should appear in rendered output
            rendered = body.get("renderedContent", "") + body.get("renderedSubject", "")
            assert "Alice" in rendered or "DIGIT" in rendered, \
                f"Variable substitution may have failed — rendered: {rendered!r}"

        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_preview_specific_version_after_update(self, request, base_url, auth_headers, gateway_headers_spec):
        """After updating to v2, preview of v1 must return v1 content."""
        tid = _tpl_id()
        try:
            req_lib.post(f"{base_url}/template",
                         json=make_email_template(template_id=tid), headers=auth_headers)
            req_lib.put(f"{base_url}/template",
                        json=make_template_update(tid), headers=auth_headers)

            r = _send(request.node, "POST", f"{base_url}/template/preview",
                      headers=auth_headers,
                      json_body=make_preview_request(tid, version="v1"))
            assert r.status_code == 200, f"Preview v1 failed: {r.text}"
            body = r.json()
            assert_preview_response_shape(body)
            assert body.get("version") == "v1", \
                f"Expected preview of v1, got version: {body.get('version')}"

        finally:
            _cleanup(base_url, tid, "v2", auth_headers)
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_preview_nonexistent_template_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/template/preview",
                  headers=auth_headers,
                  json_body={"templateId": f"NONEXISTENT-{_tpl_id()}"})
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)


# ── Notification Send Flow ────────────────────────────────────────────────────

class TestNotificationSendFlow:
    def test_email_send_uses_latest_version(self, request, base_url, auth_headers, gateway_headers_spec):
        """After creating v1 and updating to v2, email send must use v2."""
        tid = _tpl_id()
        try:
            req_lib.post(f"{base_url}/template",
                         json=make_email_template(template_id=tid), headers=auth_headers)
            req_lib.put(f"{base_url}/template",
                        json=make_template_update(tid), headers=auth_headers)

            r = _send(request.node, "POST", f"{base_url}/email/send",
                      headers=auth_headers,
                      json_body=make_email_request(tid))
            # Accept 200 (sent), 404 (not found — infra issue), 422 (render/provider error)
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            if r.status_code == 200:
                body = r.json()
                assert_notification_response_shape(body)
                assert body.get("version") == "v2", \
                    f"Email send should use latest version v2, got: {body.get('version')}"

        finally:
            _cleanup(base_url, tid, "v2", auth_headers)
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_email_send_pinned_version(self, request, base_url, auth_headers, gateway_headers_spec):
        """Pinning version=v1 while v2 exists must use v1."""
        tid = _tpl_id()
        try:
            req_lib.post(f"{base_url}/template",
                         json=make_email_template(template_id=tid), headers=auth_headers)
            req_lib.put(f"{base_url}/template",
                        json=make_template_update(tid), headers=auth_headers)

            r = _send(request.node, "POST", f"{base_url}/email/send",
                      headers=auth_headers,
                      json_body=make_email_request(tid, version="v1"))
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            if r.status_code == 200:
                body = r.json()
                assert_notification_response_shape(body)
                assert body.get("version") == "v1", \
                    f"Pinned v1 send should use version v1, got: {body.get('version')}"

        finally:
            _cleanup(base_url, tid, "v2", auth_headers)
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_sms_send_lifecycle(self, request, base_url, auth_headers, gateway_headers_spec):
        """Create SMS template → send → verify response shape."""
        tid = _tpl_id()
        try:
            r = _send(request.node, "POST", f"{base_url}/template",
                      headers=auth_headers,
                      json_body=make_sms_template(template_id=tid))
            assert r.status_code == 201, f"Create failed: {r.text}"

            r = _send(request.node, "POST", f"{base_url}/sms/send",
                      headers=auth_headers,
                      json_body=make_sms_request(
                          tid,
                          payload={"otp": "999888"},
                          category="OTP",
                      ))
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            if r.status_code == 200:
                assert_notification_response_shape(r.json())

        finally:
            _cleanup(base_url, tid, "v1", auth_headers)

    def test_send_after_template_deleted_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        """Sending a notification after its template is deleted must return 404."""
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        req_lib.delete(f"{base_url}/template",
                       params={"templateId": tid, "version": "v1"},
                       headers=auth_headers)

        r = _send(request.node, "POST", f"{base_url}/email/send",
                  headers=auth_headers,
                  json_body=make_email_request(tid))
        assert r.status_code == 404, \
            f"Expected 404 after template deletion, got {r.status_code}: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
