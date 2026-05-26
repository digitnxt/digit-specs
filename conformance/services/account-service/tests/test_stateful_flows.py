import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_tenant_request,
    make_update_tenant_request,
    make_tenant_config_request,
    make_update_tenant_config_request,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_tenant_response_nonempty,
    assert_tenant_config_response,
    assert_tenant_config_response_nonempty,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── Tenant lifecycle ───────────────────────────────────────────────────────
# NOTE: DELETE /accounts is destructive (tears down Keycloak realm and all
# associated configs/data). The lifecycle test here covers create → read →
# update only; the cleanup DELETE is intentionally omitted so the run
# doesn't depend on a destructive operation completing successfully.

class TestTenantLifecycle:
    def test_create_read_update_tenant(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # CREATE
        body = make_tenant_request()
        sent_name = body["tenant"]["name"]
        create_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=body)
        assert create_r.status_code == 201, f"create failed: {create_r.text}"
        assert_gateway_headers(create_r, gateway_headers_spec)
        assert_tenant_response_nonempty(create_r.json())
        tenant = create_r.json()["tenants"][0]
        tenant_id = tenant["id"]

        # READ via list-by-name
        list_r = _send(request.node, "GET", f"{base_url}/accounts",
                       headers=auth_headers, params={"name": sent_name})
        assert list_r.status_code == 200, f"list failed: {list_r.text}"
        found = [t for t in list_r.json()["tenants"] if t["id"] == tenant_id]
        assert found, f"created tenant '{tenant_id}' not found via name filter"

        # UPDATE — server only applies isActive and additionalAttributes
        update_r = _send(request.node, "PUT", f"{base_url}/accounts/{tenant_id}",
                         headers=auth_headers,
                         json_body=make_update_tenant_request(isActive=False))
        assert update_r.status_code == 200, f"update failed: {update_r.text}"
        assert_gateway_headers(update_r, gateway_headers_spec)
        updated = update_r.json()["tenants"][0]
        assert updated["id"] == tenant_id
        assert updated["isActive"] is False, \
            f"update should have toggled isActive to False, got {updated.get('isActive')}"

    def test_created_tenant_appears_in_unfiltered_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        assert create_r.status_code == 201, f"create failed: {create_r.text}"
        tenant_id = create_r.json()["tenants"][0]["id"]

        list_r = _send(request.node, "GET", f"{base_url}/accounts",
                       headers=auth_headers)
        assert list_r.status_code == 200, f"list failed: {list_r.text}"
        ids = [t["id"] for t in list_r.json()["tenants"]]
        assert tenant_id in ids, \
            f"created tenant '{tenant_id}' missing from unfiltered list"


# ── Tenant config lifecycle ────────────────────────────────────────────────
# A config is keyed to a parent tenant via `code`. Each scenario provisions
# its own tenant first so the test is self-contained.

class TestTenantConfigLifecycle:
    def _provision_tenant(self, request, base_url, auth_headers):
        """Create a tenant and return its code for tying a config to it."""
        tenant_r = _send(request.node, "POST", f"{base_url}/accounts",
                         headers=auth_headers, json_body=make_tenant_request())
        if tenant_r.status_code != 201:
            pytest.skip(f"tenant create failed, cannot test config: {tenant_r.text}")
        tenant = tenant_r.json()["tenants"][0]
        code = tenant.get("code")
        if not code:
            pytest.skip("created tenant has no code field, cannot link config")
        return code

    def test_create_read_update_config(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        tenant_code = self._provision_tenant(request, base_url, auth_headers)

        # CREATE
        create_r = _send(request.node, "POST", f"{base_url}/config",
                         headers=auth_headers,
                         json_body=make_tenant_config_request(tenant_code=tenant_code))
        assert create_r.status_code == 201, f"config create failed: {create_r.text}"
        assert_gateway_headers(create_r, gateway_headers_spec)
        assert_tenant_config_response_nonempty(create_r.json())
        cfg = create_r.json()["tenantConfigs"][0]
        cfg_id = cfg["id"]

        # READ via list-by-code
        list_r = _send(request.node, "GET", f"{base_url}/config",
                       headers=auth_headers, params={"code": tenant_code})
        assert list_r.status_code == 200, f"list failed: {list_r.text}"
        assert_tenant_config_response(list_r.json())
        found = [c for c in list_r.json()["tenantConfigs"] if c.get("id") == cfg_id]
        assert found, f"created config '{cfg_id}' not found via code filter"

        # UPDATE — must re-supply every existing document so the server
        # doesn't trip MISSING_DOCUMENT validation. Pull the current list
        # from the create response and pass it back verbatim.
        existing_docs = cfg.get("documents") or []
        update_r = _send(
            request.node, "PUT", f"{base_url}/config/{cfg_id}",
            headers=auth_headers,
            json_body=make_update_tenant_config_request(
                tenant_code=tenant_code, documents=existing_docs,
            ),
        )
        assert update_r.status_code == 200, f"config update failed: {update_r.text}"
        assert_gateway_headers(update_r, gateway_headers_spec)
        assert_tenant_config_response_nonempty(update_r.json())
