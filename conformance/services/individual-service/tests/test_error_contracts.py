import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers, assert_json_content_type
from tests.helpers.factories import make_invalid_individual, make_invalid_config


def _send(node, method, url, headers=None, json_body=None):
    """Prepare, attach cURL for HTML report, then send."""
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── Individual negative contracts ─────────────────────────────────────────────

class TestIndividualNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("missing_required"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_dob_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("missing_dob"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_dob_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_dob_format"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_gender_enum_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_gender"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_mobile_too_short_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("mobile_too_short"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_email_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("invalid_email"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_name_too_short_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers,
                         json_body=make_invalid_individual("name_too_short"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/individuals")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad_headers = {**auth_headers, "Authorization": "Bearer bad-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/individuals",
                         headers=bad_headers)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_nonexistent_individual_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_individual_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body={"name": "Ghost User", "dateOfBirth": "1990-01-01"})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_individual_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "DELETE",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── Config negative contracts ─────────────────────────────────────────────────

class TestConfigNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_key_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_invalid_config("missing_key"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_value_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_invalid_config("missing_value"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_key_too_short_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_invalid_config("key_too_short"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)
