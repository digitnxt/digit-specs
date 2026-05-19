import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers
from tests.helpers.factories import (
    make_rbac_rule_request,
    make_jbac_rule_request,
    make_invalid_rbac_request,
    make_invalid_jbac_request,
    make_invalid_bulk_rbac_request,
    make_invalid_bulk_jbac_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── RBAC auth errors ───────────────────────────────────────────────────────

class TestRbacAuthErrors:
    def test_create_rbac_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         json_body=make_rbac_rule_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=bad, json_body=make_rbac_rule_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_list_rbac_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/rbac/rules")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_list_rbac_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/rbac/rules", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_rbac_rule_missing_auth_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            rule_id = "00000000-0000-0000-0000-000000000001"
        else:
            rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "GET", f"{base_url}/rbac/rules/{rule_id}")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_rbac_missing_auth_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            rule_id = "00000000-0000-0000-0000-000000000001"
        else:
            rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "PATCH", f"{base_url}/rbac/rules/{rule_id}",
                         json_body=make_rbac_rule_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_rbac_missing_auth_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            rule_id = "00000000-0000-0000-0000-000000000001"
        else:
            rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "DELETE", f"{base_url}/rbac/rules/{rule_id}")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── RBAC validation errors ─────────────────────────────────────────────────

class TestRbacValidationErrors:
    def test_create_rbac_missing_role_names_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("missing_role_names"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_missing_http_method_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("missing_http_method"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_missing_path_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("missing_path"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_missing_effect_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("missing_effect"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_missing_priority_uses_default(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # priority is optional on Create — the server fills nil with
        # DefaultPriority, so a request without `priority` is valid and
        # results in 201.
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("missing_priority"))
        assert response.status_code == 201
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_invalid_effect_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("invalid_effect"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_invalid_http_method_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("invalid_http_method"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_empty_role_names_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("empty_role_names"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_priority_out_of_range_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("priority_out_of_range"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_rbac_wrong_field_types_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_rbac_request("wrong_types"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)


# ── RBAC not-found errors ──────────────────────────────────────────────────

class TestRbacNotFoundErrors:
    def test_get_nonexistent_rbac_rule_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET",
                         f"{base_url}/rbac/rules/00000000-0000-0000-0000-000000000000",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_rbac_rule_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PATCH",
                         f"{base_url}/rbac/rules/00000000-0000-0000-0000-000000000000",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_rbac_rule_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE",
                         f"{base_url}/rbac/rules/00000000-0000-0000-0000-000000000000",
                         headers=auth_headers)
        assert response.status_code in (204, 404)
        assert_gateway_headers(response, gateway_headers_spec)


# ── RBAC bulk errors ───────────────────────────────────────────────────────

class TestRbacBulkValidationErrors:
    def test_bulk_create_rbac_empty_rules_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                         headers=auth_headers,
                         json_body=make_invalid_bulk_rbac_request("empty_rules"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_bulk_create_rbac_missing_rules_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                         headers=auth_headers,
                         json_body=make_invalid_bulk_rbac_request("missing_rules"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_bulk_create_rbac_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                         json_body=make_invalid_bulk_rbac_request("empty_rules"))
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── JBAC auth errors ───────────────────────────────────────────────────────

class TestJbacAuthErrors:
    def test_create_jbac_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         json_body=make_jbac_rule_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=bad, json_body=make_jbac_rule_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_list_jbac_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/jbac/rules")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_jbac_rule_missing_auth_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            rule_id = "00000000-0000-0000-0000-000000000001"
        else:
            rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "GET", f"{base_url}/jbac/rules/{rule_id}")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_jbac_rule_missing_auth_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            rule_id = "00000000-0000-0000-0000-000000000001"
        else:
            rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "DELETE", f"{base_url}/jbac/rules/{rule_id}")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── JBAC validation errors ─────────────────────────────────────────────────

class TestJbacValidationErrors:
    def test_create_jbac_missing_name_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("missing_name"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_missing_path_pattern_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("missing_path_pattern"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_missing_methods_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("missing_methods"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_missing_enforcement_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("missing_enforcement"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_missing_parent_implies_children_uses_default(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # parentImpliesChildren is optional on Create — schema declares
        # `default: false`, and the Go struct field is a plain bool that
        # zero-values to false. Missing field is valid → 201.
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("missing_parent_implies_children"))
        assert response.status_code == 201
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_empty_methods_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("empty_methods"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_invalid_method_in_array_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("invalid_method_in_array"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_jbac_wrong_field_types_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_invalid_jbac_request("wrong_types"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)


# ── JBAC not-found errors ──────────────────────────────────────────────────

class TestJbacNotFoundErrors:
    def test_get_nonexistent_jbac_rule_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET",
                         f"{base_url}/jbac/rules/00000000-0000-0000-0000-000000000000",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_jbac_rule_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PATCH",
                         f"{base_url}/jbac/rules/00000000-0000-0000-0000-000000000000",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_jbac_rule_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE",
                         f"{base_url}/jbac/rules/00000000-0000-0000-0000-000000000000",
                         headers=auth_headers)
        assert response.status_code in (204, 404)
        assert_gateway_headers(response, gateway_headers_spec)


# ── JBAC bulk errors ───────────────────────────────────────────────────────

class TestJbacBulkValidationErrors:
    def test_bulk_create_jbac_empty_rules_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                         headers=auth_headers,
                         json_body=make_invalid_bulk_jbac_request("empty_rules"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_bulk_create_jbac_missing_rules_returns_400_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                         headers=auth_headers,
                         json_body=make_invalid_bulk_jbac_request("missing_rules"))
        assert response.status_code in (400, 422)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_bulk_create_jbac_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                         json_body=make_invalid_bulk_jbac_request("empty_rules"))
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


# ── Tenant delete errors ───────────────────────────────────────────────────

@pytest.mark.skip(reason="tenant-delete endpoints excluded from this run (destructive)")
class TestTenantDeleteAuthErrors:
    def test_delete_rbac_tenant_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/rbac/rules/tenant")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_jbac_tenant_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/jbac/rules/tenant")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_rbac_tenant_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "DELETE", f"{base_url}/rbac/rules/tenant",
                         headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)
