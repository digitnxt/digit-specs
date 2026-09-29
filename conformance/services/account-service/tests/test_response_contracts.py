"""Success-path response contracts for the Account/Tenant service (v3).

Tokenless (no auth plugin on this route). Every tenant created here is tracked
by the `tenant_factory` fixture and hard-deleted at teardown (delete cascades
and removes the Keycloak realm).
"""
import pytest
import requests as req_lib
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_tenant_shape,
    assert_tenant_list_response,
    assert_tenant_config_shape,
    assert_tenant_config_list_response,
    assert_signup_initiate_response,
    assert_field_types,
)
from tests.helpers.factories import (
    make_tenant_request,
    make_update_tenant_request,
    make_tenant_config_request,
    make_signup_initiate_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    node._curl_request = prepared
    return req_lib.Session().send(prepared, timeout=30)


# ── Tenant ───────────────────────────────────────────────────────────────────

class TestTenantCreateContract:
    def test_create_returns_201(self, request, base_url, auth_headers, gateway_headers_spec, tenant_factory):
        r = tenant_factory.create()
        assert r.status_code == 201, f"create failed: {r.text}"
        node = request.node
        node._curl_request = r.request
        assert_json_content_type(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert_tenant_shape(r.json())

    def test_create_generates_id_and_code(self, request, base_url, auth_headers, tenant_factory):
        r = tenant_factory.create()
        assert r.status_code == 201, f"create failed: {r.text}"
        t = r.json()
        assert t.get("id"), "server must generate tenant.id"
        assert t.get("code"), "server must derive tenant.code from name"

    def test_create_echoes_email(self, request, base_url, auth_headers, tenant_factory):
        body = make_tenant_request()
        r = tenant_factory.create(**body)
        assert r.status_code == 201, f"create failed: {r.text}"
        assert r.json()["email"] == body["email"]

    def test_create_field_types(self, request, base_url, auth_headers, tenant_factory):
        r = tenant_factory.create()
        assert r.status_code == 201, f"create failed: {r.text}"
        assert_field_types(r.json(), {"id": str, "code": str, "name": str, "email": str, "isActive": bool})

    def test_create_password_not_echoed(self, request, base_url, auth_headers, tenant_factory):
        r = tenant_factory.create(password="ConformancePwd123!")
        assert r.status_code == 201, f"create failed: {r.text}"
        assert "password" not in r.json(), "writeOnly password must not be returned"


class TestTenantListContract:
    def test_list_returns_200(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "GET", f"{base_url}/tenants", headers=auth_headers)
        assert r.status_code == 200, f"list failed: {r.text}"
        assert_json_content_type(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert_tenant_list_response(r.json())

    def test_list_filter_by_code(self, request, base_url, auth_headers, tenant_factory):
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"create failed: {cr.text}")
        code = cr.json()["code"]
        r = _send(request.node, "GET", f"{base_url}/tenants", headers=auth_headers, params={"code": code})
        assert r.status_code == 200, f"list failed: {r.text}"
        body = r.json()
        assert_tenant_list_response(body)
        assert any(t["code"] == code for t in body["tenants"]), \
            f"filter code={code} did not return the created tenant"


class TestTenantUpdateContract:
    def test_update_returns_200(self, request, base_url, auth_headers, gateway_headers_spec, tenant_factory):
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"create failed: {cr.text}")
        tid = cr.json()["id"]
        r = _send(request.node, "PUT", f"{base_url}/tenants/{tid}",
                  headers=auth_headers, json_body=make_update_tenant_request())
        assert r.status_code == 200, f"update failed: {r.text}"
        assert_json_content_type(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert_tenant_shape(r.json())


# ── Signup (OTP-gated — initiate only; verify needs an out-of-band OTP) ──────

class TestSignupInitiateContract:
    def test_initiate_returns_200(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "POST", f"{base_url}/tenants/registrations",
                  headers=auth_headers, json_body=make_signup_initiate_request())
        # A flaky/rate-limited downstream OTP service may legitimately 429/503.
        assert r.status_code in (200, 429, 503), f"unexpected: {r.status_code} {r.text}"
        if r.status_code == 200:
            assert_json_content_type(r)
            assert_gateway_headers(r, gateway_headers_spec)
            assert_signup_initiate_response(r.json())


# ── TenantConfig (scoped by X-Tenant-Id = tenant UUID) ───────────────────────

class TestTenantConfigCreateContract:
    def test_create_config_returns_201(self, request, base_url, auth_headers, gateway_headers_spec, tenant_factory):
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"tenant create failed: {cr.text}")
        tid = cr.json()["id"]
        headers = {**auth_headers, "X-Tenant-Id": tid}
        r = _send(request.node, "POST", f"{base_url}/config",
                  headers=headers, json_body=make_tenant_config_request(tid))
        assert r.status_code == 201, f"config create failed: {r.text}"
        assert_json_content_type(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert_tenant_config_shape(r.json())


class TestTenantConfigListContract:
    def test_list_configs_returns_200(self, request, base_url, auth_headers, gateway_headers_spec, tenant_factory):
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"tenant create failed: {cr.text}")
        tid = cr.json()["id"]
        headers = {**auth_headers, "X-Tenant-Id": tid}
        r = _send(request.node, "GET", f"{base_url}/config", headers=headers)
        assert r.status_code == 200, f"config list failed: {r.text}"
        assert_json_content_type(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert_tenant_config_list_response(r.json())
