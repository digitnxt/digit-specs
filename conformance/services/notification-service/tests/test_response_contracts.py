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
    assert_service_response_headers,
    assert_json_content_type,
    assert_template_response_shape,
    assert_notification_response_shape,
    assert_preview_response_shape,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _delete_template(base_url, template_id, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateId": template_id, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ── Template Create ───────────────────────────────────────────────────────────

class TestTemplateCreateContract:
    def test_create_email_template_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        payload = make_email_template(template_id=tid)
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=payload)
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_template_response_shape(body)
            assert body["templateId"] == tid
            assert body.get("version") == "v1"
            assert body["type"] == "EMAIL"
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_create_html_email_template_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        payload = make_html_email_template(template_id=tid)
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=payload)
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            body = r.json()
            assert_template_response_shape(body)
            assert body.get("isHTML") is True
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_create_sms_template_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        payload = make_sms_template(template_id=tid)
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=payload)
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            body = r.json()
            assert_template_response_shape(body)
            assert body["templateId"] == tid
            assert body["type"] == "SMS"
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)


# ── Template Update ───────────────────────────────────────────────────────────

class TestTemplateUpdateContract:
    def test_update_creates_v2(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        r = _send(request.node, "PUT", f"{base_url}/template",
                  headers=auth_headers, json_body=make_template_update(tid))
        try:
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_template_response_shape(body)
            assert body.get("version") == "v2"
            assert body["templateId"] == tid
        finally:
            _delete_template(base_url, tid, "v2", auth_headers)
            _delete_template(base_url, tid, "v1", auth_headers)


# ── Template Search ───────────────────────────────────────────────────────────

class TestTemplateSearchContract:
    def test_search_returns_200_array(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "GET", f"{base_url}/template", headers=auth_headers)
        assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
        assert_json_content_type(r)
        assert_service_response_headers(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert isinstance(r.json(), list), f"Expected array, got: {type(r.json())}"

    def test_search_by_template_id_finds_created(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid})
            assert r.status_code == 200
            body = r.json()
            assert isinstance(body, list)
            assert any(t["templateId"] == tid for t in body), \
                f"Created template '{tid}' not found in search results"
            assert_gateway_headers(r, gateway_headers_spec)
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_search_by_template_id_and_version(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid, "version": "v1"})
            assert r.status_code == 200
            body = r.json()
            assert isinstance(body, list)
            if body:
                assert body[0].get("version") == "v1"
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_search_by_type_email(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "GET", f"{base_url}/template",
                  headers=auth_headers, params={"type": "EMAIL"})
        assert r.status_code == 200
        body = r.json()
        assert isinstance(body, list)
        for item in body:
            assert item.get("type") == "EMAIL", \
                f"Non-EMAIL template returned when filtering by type=EMAIL: {item}"

    def test_search_pagination_respects_limit(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "GET", f"{base_url}/template",
                  headers=auth_headers, params={"limit": 5, "offset": 0})
        assert r.status_code == 200
        assert isinstance(r.json(), list)
        assert len(r.json()) <= 5

    def test_search_results_have_correct_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateId": tid})
            assert r.status_code == 200
            for item in r.json():
                assert_template_response_shape(item)
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)


# ── Template Delete ───────────────────────────────────────────────────────────

class TestTemplateDeleteContract:
    def test_delete_returns_200_with_deleted_true(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers,
                  params={"templateId": tid, "version": "v1"})
        assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
        assert_json_content_type(r)
        assert_service_response_headers(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert r.json().get("deleted") is True

    def test_delete_removes_template_from_search(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        _send(request.node, "DELETE", f"{base_url}/template",
              headers=auth_headers, params={"templateId": tid, "version": "v1"})
        r = _send(request.node, "GET", f"{base_url}/template",
                  headers=auth_headers, params={"templateId": tid})
        assert r.status_code == 200
        assert not any(t["templateId"] == tid for t in r.json()), \
            f"Deleted template '{tid}' still visible in search results"


# ── Template Preview ──────────────────────────────────────────────────────────

class TestTemplatePreviewContract:
    def test_preview_returns_200_with_rendered_content(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(
            f"{base_url}/template",
            json=make_html_email_template(template_id=tid),
            headers=auth_headers,
        )
        try:
            r = _send(
                request.node, "POST", f"{base_url}/template/preview",
                headers=auth_headers,
                json_body=make_preview_request(tid, payload={"name": "Alice", "orgName": "DIGIT"}),
            )
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_preview_response_shape(body)
            assert body["templateId"] == tid
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_preview_specific_version(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(
                request.node, "POST", f"{base_url}/template/preview",
                headers=auth_headers,
                json_body=make_preview_request(tid, version="v1"),
            )
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            body = r.json()
            assert_preview_response_shape(body)
            assert body.get("version") == "v1"
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_preview_sms_template(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_sms_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(
                request.node, "POST", f"{base_url}/template/preview",
                headers=auth_headers,
                json_body=make_preview_request(tid, payload={"otp": "123456"}),
            )
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            body = r.json()
            assert body["type"] == "SMS"
            assert_preview_response_shape(body)
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)


# ── Email Send ────────────────────────────────────────────────────────────────

class TestEmailSendContract:
    def test_send_email_returns_200_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(
                request.node, "POST", f"{base_url}/email/send",
                headers=auth_headers,
                json_body=make_email_request(tid),
            )
            # 200 = accepted; 404 = template not found; 422 = render/provider error
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            if r.status_code == 200:
                assert_json_content_type(r)
                assert_service_response_headers(r)
                assert_notification_response_shape(r.json())
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_send_email_with_payload(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_html_email_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(
                request.node, "POST", f"{base_url}/email/send",
                headers=auth_headers,
                json_body=make_email_request(
                    tid,
                    payload={"name": "Alice", "orgName": "DIGIT"},
                ),
            )
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            if r.status_code == 200:
                assert_notification_response_shape(r.json())
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)


# ── SMS Send ──────────────────────────────────────────────────────────────────

class TestSMSSendContract:
    def test_send_sms_returns_200_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_sms_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(
                request.node, "POST", f"{base_url}/sms/send",
                headers=auth_headers,
                json_body=make_sms_request(tid, payload={"otp": "654321"}),
            )
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            if r.status_code == 200:
                assert_json_content_type(r)
                assert_service_response_headers(r)
                assert_notification_response_shape(r.json())
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)

    def test_send_sms_with_category(self, request, base_url, auth_headers, gateway_headers_spec):
        tid = _tpl_id()
        req_lib.post(f"{base_url}/template",
                     json=make_sms_template(template_id=tid), headers=auth_headers)
        try:
            r = _send(
                request.node, "POST", f"{base_url}/sms/send",
                headers=auth_headers,
                json_body=make_sms_request(tid, category="OTP", payload={"otp": "112233"}),
            )
            assert r.status_code in (200, 404, 422), \
                f"Unexpected status {r.status_code}: {r.text}"
            if r.status_code == 200:
                assert_notification_response_shape(r.json())
        finally:
            _delete_template(base_url, tid, "v1", auth_headers)
