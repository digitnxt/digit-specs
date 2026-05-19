import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_rbac_rule_response,
    assert_rbac_list_response,
    assert_jbac_rule_response,
    assert_jbac_list_response,
    assert_bulk_create_response,
    assert_version_response,
    assert_internal_rbac_response,
    assert_internal_jbac_response,
    assert_field_types,
)
from tests.helpers.factories import (
    make_rbac_rule_request,
    make_update_rbac_rule_request,
    make_jbac_rule_request,
    make_update_jbac_rule_request,
    make_bulk_rbac_request,
    make_bulk_jbac_request,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── RBAC Rules ─────────────────────────────────────────────────────────────

class TestRbacCreateContract:
    def test_create_rbac_rule_returns_201(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_rbac_rule_response(response.json())

    def test_create_rbac_rule_generates_uuid_id(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        assert response.status_code == 201
        rule = response.json()["rule"]
        assert "id" in rule and len(rule["id"]) > 0

    def test_create_rbac_rule_echoes_effect(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_rbac_rule_request(effect="DENY"))
        assert response.status_code == 201
        assert response.json()["rule"]["effect"] == "DENY"

    def test_create_rbac_rule_echoes_priority(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers,
                         json_body=make_rbac_rule_request(priority=42))
        assert response.status_code == 201
        assert response.json()["rule"]["priority"] == 42

    def test_create_rbac_rule_field_types(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        assert response.status_code == 201
        rule = response.json()["rule"]
        assert_field_types(rule, {
            "id": str, "httpMethod": str, "path": str,
            "effect": str, "priority": int, "enabled": bool,
        })
        assert isinstance(rule["roleNames"], list)


class TestRbacListContract:
    def test_list_rbac_rules_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/rbac/rules",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_rbac_list_response(response.json())

    def test_list_rbac_rules_pagination_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/rbac/rules",
                         headers=auth_headers, params={"limit": 5, "offset": 0})
        assert response.status_code == 200
        body = response.json()
        assert body["limit"] <= 5
        assert body["offset"] == 0
        assert body["total"] >= 0

    def test_list_rbac_rules_filter_by_effect(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        _send(request.node, "POST", f"{base_url}/rbac/rules",
              headers=auth_headers, json_body=make_rbac_rule_request(effect="ALLOW"))

        response = _send(request.node, "GET", f"{base_url}/rbac/rules",
                         headers=auth_headers, params={"effect": "ALLOW"})
        assert response.status_code == 200
        body = response.json()
        assert_rbac_list_response(body)
        for rule in body["rules"]:
            assert rule["effect"] == "ALLOW", \
                f"Filtered ALLOW but got rule with effect='{rule['effect']}'"

    def test_list_rbac_rules_filter_by_http_method(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        _send(request.node, "POST", f"{base_url}/rbac/rules",
              headers=auth_headers, json_body=make_rbac_rule_request(httpMethod="POST"))

        response = _send(request.node, "GET", f"{base_url}/rbac/rules",
                         headers=auth_headers, params={"httpMethod": "POST"})
        assert response.status_code == 200
        assert_rbac_list_response(response.json())


class TestRbacSingleContract:
    def test_get_rbac_rule_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test single GET")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "GET", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_rbac_rule_response(response.json())

    def test_get_rbac_rule_id_matches_path_param(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test single GET")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "GET", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["rule"]["id"] == rule_id


class TestRbacUpdateContract:
    def test_update_rbac_rule_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test update")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "PATCH", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers, json_body=make_update_rbac_rule_request())
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_rbac_rule_response(response.json())

    def test_update_rbac_rule_reflects_new_values(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request(effect="ALLOW"))
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test update")
        rule_id = create_r.json()["rule"]["id"]

        update_body = make_update_rbac_rule_request(effect="DENY", priority=999)
        response = _send(request.node, "PATCH", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers, json_body=update_body)
        assert response.status_code == 200
        updated_rule = response.json()["rule"]
        assert updated_rule["effect"] == "DENY"
        assert updated_rule["priority"] == 999


class TestRbacDeleteContract:
    def test_delete_rbac_rule_returns_204(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test delete")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "DELETE", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 204
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_rbac_rule_makes_it_not_found(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test delete")
        rule_id = create_r.json()["rule"]["id"]

        _send(request.node, "DELETE", f"{base_url}/rbac/rules/{rule_id}",
              headers=auth_headers)

        response = _send(request.node, "GET", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 404


class TestRbacBulkCreateContract:
    def test_bulk_create_rbac_rules_returns_201(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                         headers=auth_headers, json_body=make_bulk_rbac_request(count=3))
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_bulk_create_response(response.json())

    def test_bulk_create_rbac_rules_created_count(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                         headers=auth_headers, json_body=make_bulk_rbac_request(count=2))
        assert response.status_code == 201
        body = response.json()
        # Bulk insert is atomic — on success all submitted rules are created.
        assert body["created"] == 2, \
            f"expected all 2 submitted rules to be created, got {body['created']}"


# ── JBAC Rules ─────────────────────────────────────────────────────────────

class TestJbacCreateContract:
    def test_create_jbac_rule_returns_201(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_jbac_rule_response(response.json())

    def test_create_jbac_rule_generates_uuid_id(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        assert response.status_code == 201
        rule = response.json()["rule"]
        assert "id" in rule and len(rule["id"]) > 0

    def test_create_jbac_rule_echoes_enforcement(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_jbac_rule_request(enforcement="OPTIONAL"))
        assert response.status_code == 201
        assert response.json()["rule"]["enforcement"] == "OPTIONAL"

    def test_create_jbac_rule_field_types(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        assert response.status_code == 201
        rule = response.json()["rule"]
        assert_field_types(rule, {
            "id": str, "name": str, "pathPattern": str, "enforcement": str,
        })
        assert isinstance(rule["methods"], list)


class TestJbacListContract:
    def test_list_jbac_rules_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/jbac/rules",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_jbac_list_response(response.json())

    def test_list_jbac_rules_pagination_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/jbac/rules",
                         headers=auth_headers, params={"limit": 5, "offset": 0})
        assert response.status_code == 200
        body = response.json()
        assert body["limit"] <= 5
        assert body["offset"] == 0
        assert body["total"] >= 0

    def test_list_jbac_rules_filter_by_name(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_name = f"ConformanceUnique-{make_jbac_rule_request()['name']}"
        _send(request.node, "POST", f"{base_url}/jbac/rules",
              headers=auth_headers, json_body=make_jbac_rule_request(name=rule_name))

        response = _send(request.node, "GET", f"{base_url}/jbac/rules",
                         headers=auth_headers, params={"name": rule_name})
        assert response.status_code == 200
        assert_jbac_list_response(response.json())


class TestJbacSingleContract:
    def test_get_jbac_rule_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test single GET")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "GET", f"{base_url}/jbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_jbac_rule_response(response.json())

    def test_get_jbac_rule_id_matches_path_param(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test single GET")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "GET", f"{base_url}/jbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["rule"]["id"] == rule_id


class TestJbacUpdateContract:
    def test_update_jbac_rule_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test update")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "PATCH", f"{base_url}/jbac/rules/{rule_id}",
                         headers=auth_headers, json_body=make_update_jbac_rule_request())
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_jbac_rule_response(response.json())

    def test_update_jbac_rule_reflects_new_values(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers,
                         json_body=make_jbac_rule_request(enforcement="REQUIRED",
                                                           parentImpliesChildren=False))
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test update")
        rule_id = create_r.json()["rule"]["id"]

        update_body = make_update_jbac_rule_request(enforcement="OPTIONAL",
                                                     parentImpliesChildren=True)
        response = _send(request.node, "PATCH", f"{base_url}/jbac/rules/{rule_id}",
                         headers=auth_headers, json_body=update_body)
        assert response.status_code == 200
        updated_rule = response.json()["rule"]
        assert updated_rule["enforcement"] == "OPTIONAL"
        if "parentImpliesChildren" in updated_rule:
            assert updated_rule["parentImpliesChildren"] is True


class TestJbacDeleteContract:
    def test_delete_jbac_rule_returns_204(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test delete")
        rule_id = create_r.json()["rule"]["id"]

        response = _send(request.node, "DELETE", f"{base_url}/jbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 204
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_jbac_rule_makes_it_not_found(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                         headers=auth_headers, json_body=make_jbac_rule_request())
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test delete")
        rule_id = create_r.json()["rule"]["id"]

        _send(request.node, "DELETE", f"{base_url}/jbac/rules/{rule_id}",
              headers=auth_headers)

        response = _send(request.node, "GET", f"{base_url}/jbac/rules/{rule_id}",
                         headers=auth_headers)
        assert response.status_code == 404


class TestJbacBulkCreateContract:
    def test_bulk_create_jbac_rules_returns_201(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                         headers=auth_headers, json_body=make_bulk_jbac_request(count=3))
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_bulk_create_response(response.json())

    def test_bulk_create_jbac_rules_created_count(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                         headers=auth_headers, json_body=make_bulk_jbac_request(count=2))
        assert response.status_code == 201
        body = response.json()
        # Bulk insert is atomic — on success all submitted rules are created.
        assert body["created"] == 2, \
            f"expected all 2 submitted rules to be created, got {body['created']}"


# ── Internal endpoints ─────────────────────────────────────────────────────
# These endpoints are not exposed via Kong — they are consumed directly by the
# Kong plugin over the Docker-internal network.  Skip them in gateway-facing runs.

@pytest.mark.skip(reason="internal endpoint not exposed via Kong gateway")
class TestInternalRbacContract:
    def test_internal_list_rbac_rules_returns_200(
        self, request, base_url, gateway_headers_spec
    ):
        # Internal endpoints have security: [] — no auth required
        response = _send(request.node, "GET", f"{base_url}/internal/rbac/rules")
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_internal_rbac_response(response.json())

    def test_internal_rbac_version_returns_32_char_hash(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/internal/rbac/rules/version")
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_version_response(response.json())

    def test_internal_rbac_pagination(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/internal/rbac/rules",
                         params={"limit": 10, "offset": 0})
        assert response.status_code == 200
        assert_internal_rbac_response(response.json())


@pytest.mark.skip(reason="internal endpoint not exposed via Kong gateway")
class TestInternalJbacContract:
    def test_internal_list_jbac_rules_returns_200(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/internal/jbac/rules")
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_internal_jbac_response(response.json())

    def test_internal_jbac_version_returns_32_char_hash(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/internal/jbac/rules/version")
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_version_response(response.json())

    def test_internal_jbac_pagination(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/internal/jbac/rules",
                         params={"limit": 10, "offset": 0})
        assert response.status_code == 200
        assert_internal_jbac_response(response.json())
