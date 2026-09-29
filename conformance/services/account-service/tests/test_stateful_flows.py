"""Stateful lifecycle flows for the Account/Tenant service (v3).

All tenants are created through `tenant_factory` so teardown hard-deletes them
even if an assertion fails mid-flow.
"""
import uuid
import pytest
import requests as req_lib
from tests.helpers.validators import (
    assert_tenant_shape,
    assert_tenant_list_response,
    assert_delete_response,
    assert_tenant_config_shape,
    assert_tenant_config_list_response,
    assert_signup_initiate_response,
)
from tests.helpers.factories import (
    make_update_tenant_request,
    make_tenant_config_request,
    make_update_tenant_config_request,
    make_signup_initiate_request,
    make_signup_resend_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    node._curl_request = prepared
    return req_lib.Session().send(prepared, timeout=30)


class TestTenantLifecycle:
    def test_create_read_update_delete(self, request, base_url, auth_headers, tenant_factory):
        node = request.node
        # create
        cr = tenant_factory.create()
        assert cr.status_code == 201, f"create failed: {cr.text}"
        t = cr.json(); tid, code = t["id"], t["code"]
        assert_tenant_shape(t)

        # appears in a code-filtered list
        lr = _send(node, "GET", f"{base_url}/tenants", headers=auth_headers, params={"code": code})
        assert lr.status_code == 200, f"list failed: {lr.text}"
        assert_tenant_list_response(lr.json())
        assert any(x["id"] == tid for x in lr.json()["tenants"]), "created tenant not found in list"

        # update (partial)
        ur = _send(node, "PUT", f"{base_url}/tenants/{tid}",
                   headers=auth_headers, json_body=make_update_tenant_request())
        assert ur.status_code == 200, f"update failed: {ur.text}"
        assert_tenant_shape(ur.json())

        # delete → {deleted: true}
        dr = _send(node, "DELETE", f"{base_url}/tenants/{tid}", headers=auth_headers)
        assert dr.status_code == 200, f"delete failed: {dr.text}"
        assert_delete_response(dr.json())

        # deleting again → 404 (gone)
        dr2 = _send(node, "DELETE", f"{base_url}/tenants/{tid}", headers=auth_headers)
        assert dr2.status_code == 404, f"expected 404 after delete, got {dr2.status_code}: {dr2.text}"


class TestTenantConfigLifecycle:
    def test_config_create_list_update(self, request, base_url, auth_headers, tenant_factory):
        node = request.node
        cr = tenant_factory.create()
        if cr.status_code != 201:
            pytest.skip(f"tenant create failed: {cr.text}")
        tid = cr.json()["id"]
        headers = {**auth_headers, "X-Tenant-Id": tid}

        # create config
        create = _send(node, "POST", f"{base_url}/config",
                       headers=headers, json_body=make_tenant_config_request(tid))
        assert create.status_code == 201, f"config create failed: {create.text}"
        cfg = create.json(); cfg_id = cfg["id"]
        assert_tenant_config_shape(cfg)

        # appears in scoped list
        lr = _send(node, "GET", f"{base_url}/config", headers=headers)
        assert lr.status_code == 200, f"config list failed: {lr.text}"
        assert_tenant_config_list_response(lr.json())
        assert any(c["id"] == cfg_id for c in lr.json()["configs"]), "created config not in list"

        # update config
        ur = _send(node, "PUT", f"{base_url}/config/{cfg_id}",
                   headers=headers, json_body=make_update_tenant_config_request(configValue="updated"))
        assert ur.status_code == 200, f"config update failed: {ur.text}"
        assert_tenant_config_shape(ur.json())
        # (config rows cascade-delete when the tenant is torn down by the fixture)


class TestSignupInitiateResendFlow:
    def test_initiate_then_resend(self, request, base_url, auth_headers):
        node = request.node
        ir = _send(node, "POST", f"{base_url}/tenants/registrations",
                   headers=auth_headers, json_body=make_signup_initiate_request())
        if ir.status_code in (429, 503):
            pytest.skip(f"OTP downstream unavailable/rate-limited: {ir.status_code}")
        assert ir.status_code == 200, f"initiate failed: {ir.text}"
        assert_signup_initiate_response(ir.json())
        ref = ir.json()["referenceId"]

        # immediate resend: cooldown likely not elapsed → 429 (Retry-After); or 200
        rr = _send(node, "POST", f"{base_url}/tenants/registrations/resend",
                   headers=auth_headers, json_body=make_signup_resend_request(ref))
        assert rr.status_code in (200, 429), f"unexpected resend status: {rr.status_code} {rr.text}"
