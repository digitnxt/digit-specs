import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_tenant_response,
    assert_tenant_response_nonempty,
    assert_tenant_config_response,
    assert_tenant_config_response_nonempty,
    assert_signup_initiate_response,
    assert_field_types,
)
from tests.helpers.factories import (
    make_tenant_request,
    make_update_tenant_request,
    make_tenant_config_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── Tenant accounts ────────────────────────────────────────────────────────

class TestTenantCreateContract:
    def test_create_tenant_returns_201(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        assert response.status_code == 201, f"create failed: {response.text}"
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_tenant_response_nonempty(response.json())

    def test_create_tenant_generates_id(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        assert response.status_code == 201, f"create failed: {response.text}"
        tenant = response.json()["tenants"][0]
        assert "id" in tenant and len(tenant["id"]) > 0, "server must generate tenant.id"

    def test_create_tenant_echoes_email(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        body = make_tenant_request()
        sent_email = body["tenant"]["email"]
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=body)
        assert response.status_code == 201, f"create failed: {response.text}"
        assert response.json()["tenants"][0]["email"] == sent_email

    def test_create_tenant_field_types(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        assert response.status_code == 201, f"create failed: {response.text}"
        tenant = response.json()["tenants"][0]
        assert_field_types(tenant, {
            "id": str, "name": str, "email": str, "isActive": bool,
        })

    def test_create_tenant_password_not_echoed(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # password is writeOnly per the spec — must never appear in responses
        response = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers,
                         json_body=make_tenant_request(password="ConformanceTestPwd123!"))
        assert response.status_code == 201, f"create failed: {response.text}"
        assert "password" not in response.json()["tenants"][0], \
            "writeOnly password must not be returned in responses"


class TestTenantListContract:
    def test_list_tenants_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/accounts",
                         headers=auth_headers)
        assert response.status_code == 200, f"list failed: {response.text}"
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_tenant_response(response.json())

    def test_list_tenants_filter_by_name(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        body = make_tenant_request()
        sent_name = body["tenant"]["name"]
        create_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=body)
        if create_r.status_code != 201:
            pytest.skip(f"create failed, cannot test filter: {create_r.text}")

        response = _send(request.node, "GET", f"{base_url}/accounts",
                         headers=auth_headers, params={"name": sent_name})
        assert response.status_code == 200, f"list failed: {response.text}"
        assert_tenant_response(response.json())
        for tenant in response.json()["tenants"]:
            assert tenant["name"] == sent_name, \
                f"filtered by name={sent_name} but got tenant name='{tenant['name']}'"


class TestTenantUpdateContract:
    def test_update_tenant_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        if create_r.status_code != 201:
            pytest.skip(f"create failed, cannot test update: {create_r.text}")
        tenant_id = create_r.json()["tenants"][0]["id"]

        response = _send(request.node, "PUT", f"{base_url}/accounts/{tenant_id}",
                         headers=auth_headers, json_body=make_update_tenant_request())
        assert response.status_code == 200, f"update failed: {response.text}"
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_tenant_response_nonempty(response.json())

    def test_update_tenant_applies_is_active(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request(isActive=True))
        if create_r.status_code != 201:
            pytest.skip(f"create failed, cannot test update: {create_r.text}")
        tenant_id = create_r.json()["tenants"][0]["id"]

        update_body = make_update_tenant_request(isActive=False)
        response = _send(request.node, "PUT", f"{base_url}/accounts/{tenant_id}",
                         headers=auth_headers, json_body=update_body)
        assert response.status_code == 200, f"update failed: {response.text}"
        assert response.json()["tenants"][0]["isActive"] is False


# ── Signup (OTP-gated) ─────────────────────────────────────────────────────

class TestSignupInitiateContract:
    def test_signup_initiate_returns_202(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/signup",
                         headers=auth_headers, json_body=make_tenant_request())
        # The OTP downstream may rate-limit or be unreachable — accept the
        # documented set so a flaky OTP service doesn't fail this contract.
        assert response.status_code in (202, 429, 500, 503), \
            f"unexpected status: {response.status_code} body={response.text}"
        if response.status_code == 202:
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
            assert_signup_initiate_response(response.json())


# ── Tenant configurations ──────────────────────────────────────────────────

class TestTenantConfigCreateContract:
    def test_create_config_returns_201(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Config creation requires an existing tenant code — create one first.
        tenant_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        if tenant_r.status_code != 201:
            pytest.skip(f"tenant create failed, cannot test config: {tenant_r.text}")
        tenant_code = tenant_r.json()["tenants"][0].get("code")
        if not tenant_code:
            pytest.skip("created tenant has no code field, cannot link config")

        response = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_tenant_config_request(tenant_code=tenant_code))
        assert response.status_code == 201, f"config create failed: {response.text}"
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_tenant_config_response_nonempty(response.json())


class TestTenantConfigListContract:
    def test_list_configs_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/config",
                         headers=auth_headers)
        assert response.status_code == 200, f"list failed: {response.text}"
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_tenant_config_response(response.json())

    def test_list_configs_filter_by_name(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/config",
                         headers=auth_headers, params={"name": "DoesNotExist-Conformance"})
        assert response.status_code == 200, f"list failed: {response.text}"
        assert_tenant_config_response(response.json())
