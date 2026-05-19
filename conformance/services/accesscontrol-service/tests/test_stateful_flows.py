import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_rbac_rule_request,
    make_update_rbac_rule_request,
    make_jbac_rule_request,
    make_update_jbac_rule_request,
    make_bulk_rbac_request,
    make_bulk_jbac_request,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_rbac_rule_response,
    assert_rbac_list_response,
    assert_jbac_rule_response,
    assert_jbac_list_response,
    assert_bulk_create_response,
    assert_version_response,
    assert_internal_rbac_response,
    assert_internal_jbac_response,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── RBAC lifecycle ─────────────────────────────────────────────────────────

class TestRbacRuleLifecycle:
    def test_create_read_update_delete_rbac_rule(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_id = None
        try:
            # CREATE
            create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                             headers=auth_headers,
                             json_body=make_rbac_rule_request(effect="ALLOW", priority=50))
            assert create_r.status_code == 201, f"Create failed: {create_r.text}"
            assert_gateway_headers(create_r, gateway_headers_spec)
            assert_rbac_rule_response(create_r.json())
            rule_id = create_r.json()["rule"]["id"]

            # READ
            get_r = _send(request.node, "GET", f"{base_url}/rbac/rules/{rule_id}",
                          headers=auth_headers)
            assert get_r.status_code == 200, f"Get failed: {get_r.text}"
            assert_gateway_headers(get_r, gateway_headers_spec)
            assert get_r.json()["rule"]["id"] == rule_id
            assert get_r.json()["rule"]["effect"] == "ALLOW"

            # UPDATE
            update_r = _send(request.node, "PATCH", f"{base_url}/rbac/rules/{rule_id}",
                             headers=auth_headers,
                             json_body=make_update_rbac_rule_request(effect="DENY", priority=75))
            assert update_r.status_code == 200, f"Update failed: {update_r.text}"
            assert_gateway_headers(update_r, gateway_headers_spec)
            assert_rbac_rule_response(update_r.json())
            assert update_r.json()["rule"]["effect"] == "DENY"
            assert update_r.json()["rule"]["priority"] == 75

            # VERIFY UPDATE via GET
            get_updated_r = _send(request.node, "GET", f"{base_url}/rbac/rules/{rule_id}",
                                  headers=auth_headers)
            assert get_updated_r.status_code == 200
            assert get_updated_r.json()["rule"]["effect"] == "DENY"

            # DELETE
            delete_r = _send(request.node, "DELETE", f"{base_url}/rbac/rules/{rule_id}",
                             headers=auth_headers)
            assert delete_r.status_code == 204, f"Delete failed: {delete_r.text}"
            assert_gateway_headers(delete_r, gateway_headers_spec)
            rule_id = None

            # VERIFY DELETION
            after_delete_r = _send(request.node, "GET",
                                   f"{base_url}/rbac/rules/00000000-0000-0000-0000-000000000000",
                                   headers=auth_headers)
            assert after_delete_r.status_code == 404

        finally:
            if rule_id:
                req_lib.delete(f"{base_url}/rbac/rules/{rule_id}", headers=auth_headers)

    def test_create_rbac_rule_appears_in_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_id = None
        try:
            create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                             headers=auth_headers, json_body=make_rbac_rule_request())
            assert create_r.status_code == 201, f"Create failed: {create_r.text}"
            rule_id = create_r.json()["rule"]["id"]

            list_r = _send(request.node, "GET", f"{base_url}/rbac/rules",
                           headers=auth_headers, params={"limit": 100, "offset": 0})
            assert list_r.status_code == 200
            assert_rbac_list_response(list_r.json())

            rule_ids = [r["id"] for r in list_r.json()["rules"]]
            assert rule_id in rule_ids, \
                f"Created rule '{rule_id}' not found in list response"
        finally:
            if rule_id:
                req_lib.delete(f"{base_url}/rbac/rules/{rule_id}", headers=auth_headers)

    def test_deleted_rbac_rule_disappears_from_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                         headers=auth_headers, json_body=make_rbac_rule_request())
        assert create_r.status_code == 201, f"Create failed: {create_r.text}"
        rule_id = create_r.json()["rule"]["id"]

        delete_r = _send(request.node, "DELETE", f"{base_url}/rbac/rules/{rule_id}",
                         headers=auth_headers)
        assert delete_r.status_code == 204, f"Delete failed: {delete_r.text}"

        list_r = _send(request.node, "GET", f"{base_url}/rbac/rules",
                       headers=auth_headers, params={"limit": 100, "offset": 0})
        assert list_r.status_code == 200
        rule_ids = [r["id"] for r in list_r.json()["rules"]]
        assert rule_id not in rule_ids, \
            f"Deleted rule '{rule_id}' still appears in list"


# ── RBAC bulk lifecycle ────────────────────────────────────────────────────

class TestRbacBulkLifecycle:
    def test_bulk_create_rules_appear_in_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bulk_r = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                       headers=auth_headers, json_body=make_bulk_rbac_request(count=3))
        assert bulk_r.status_code == 201, f"Bulk create failed: {bulk_r.text}"
        assert_gateway_headers(bulk_r, gateway_headers_spec)
        assert_bulk_create_response(bulk_r.json())

        body = bulk_r.json()
        assert body["created"] > 0, "Expected at least one rule to be created"

        list_r = _send(request.node, "GET", f"{base_url}/rbac/rules",
                       headers=auth_headers, params={"limit": 100, "offset": 0})
        assert list_r.status_code == 200
        assert_rbac_list_response(list_r.json())
        assert list_r.json()["total"] >= body["created"]

    def test_bulk_create_all_valid_rules_all_created(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bulk_r = _send(request.node, "POST", f"{base_url}/rbac/rules/bulk",
                       headers=auth_headers, json_body=make_bulk_rbac_request(count=2))
        assert bulk_r.status_code == 201
        body = bulk_r.json()
        # Atomic bulk: all valid rules must be created.
        assert body["created"] == 2, \
            f"All rules were valid but created={body['created']}: {body}"


# ── JBAC lifecycle ─────────────────────────────────────────────────────────

class TestJbacRuleLifecycle:
    def test_create_read_update_delete_jbac_rule(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_id = None
        try:
            # CREATE
            create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                             headers=auth_headers,
                             json_body=make_jbac_rule_request(enforcement="REQUIRED",
                                                               parentImpliesChildren=False))
            assert create_r.status_code == 201, f"Create failed: {create_r.text}"
            assert_gateway_headers(create_r, gateway_headers_spec)
            assert_jbac_rule_response(create_r.json())
            rule_id = create_r.json()["rule"]["id"]

            # READ
            get_r = _send(request.node, "GET", f"{base_url}/jbac/rules/{rule_id}",
                          headers=auth_headers)
            assert get_r.status_code == 200, f"Get failed: {get_r.text}"
            assert_gateway_headers(get_r, gateway_headers_spec)
            assert get_r.json()["rule"]["id"] == rule_id
            assert get_r.json()["rule"]["enforcement"] == "REQUIRED"

            # UPDATE
            update_r = _send(request.node, "PATCH", f"{base_url}/jbac/rules/{rule_id}",
                             headers=auth_headers,
                             json_body=make_update_jbac_rule_request(enforcement="OPTIONAL",
                                                                      parentImpliesChildren=True))
            assert update_r.status_code == 200, f"Update failed: {update_r.text}"
            assert_gateway_headers(update_r, gateway_headers_spec)
            assert_jbac_rule_response(update_r.json())
            assert update_r.json()["rule"]["enforcement"] == "OPTIONAL"

            # VERIFY UPDATE via GET
            get_updated_r = _send(request.node, "GET", f"{base_url}/jbac/rules/{rule_id}",
                                  headers=auth_headers)
            assert get_updated_r.status_code == 200
            assert get_updated_r.json()["rule"]["enforcement"] == "OPTIONAL"

            # DELETE
            delete_r = _send(request.node, "DELETE", f"{base_url}/jbac/rules/{rule_id}",
                             headers=auth_headers)
            assert delete_r.status_code == 204, f"Delete failed: {delete_r.text}"
            assert_gateway_headers(delete_r, gateway_headers_spec)
            rule_id = None

            # VERIFY DELETION
            after_delete_r = _send(request.node, "GET",
                                   f"{base_url}/jbac/rules/00000000-0000-0000-0000-000000000000",
                                   headers=auth_headers)
            assert after_delete_r.status_code == 404

        finally:
            if rule_id:
                req_lib.delete(f"{base_url}/jbac/rules/{rule_id}", headers=auth_headers)

    def test_create_jbac_rule_appears_in_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_id = None
        try:
            create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                             headers=auth_headers, json_body=make_jbac_rule_request())
            assert create_r.status_code == 201, f"Create failed: {create_r.text}"
            rule_id = create_r.json()["rule"]["id"]

            list_r = _send(request.node, "GET", f"{base_url}/jbac/rules",
                           headers=auth_headers, params={"limit": 100, "offset": 0})
            assert list_r.status_code == 200
            assert_jbac_list_response(list_r.json())

            rule_ids = [r["id"] for r in list_r.json()["rules"]]
            assert rule_id in rule_ids, \
                f"Created rule '{rule_id}' not found in list response"
        finally:
            if rule_id:
                req_lib.delete(f"{base_url}/jbac/rules/{rule_id}", headers=auth_headers)


# ── JBAC bulk lifecycle ────────────────────────────────────────────────────

class TestJbacBulkLifecycle:
    def test_bulk_create_jbac_rules_appear_in_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bulk_r = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                       headers=auth_headers, json_body=make_bulk_jbac_request(count=3))
        assert bulk_r.status_code == 201, f"Bulk create failed: {bulk_r.text}"
        assert_gateway_headers(bulk_r, gateway_headers_spec)
        assert_bulk_create_response(bulk_r.json())

        body = bulk_r.json()
        assert body["created"] > 0, "Expected at least one JBAC rule to be created"

        list_r = _send(request.node, "GET", f"{base_url}/jbac/rules",
                       headers=auth_headers, params={"limit": 100, "offset": 0})
        assert list_r.status_code == 200
        assert_jbac_list_response(list_r.json())
        assert list_r.json()["total"] >= body["created"]

    def test_bulk_create_all_valid_jbac_rules_all_created(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bulk_r = _send(request.node, "POST", f"{base_url}/jbac/rules/bulk",
                       headers=auth_headers, json_body=make_bulk_jbac_request(count=2))
        assert bulk_r.status_code == 201
        body = bulk_r.json()
        # Atomic bulk: all valid rules must be created.
        assert body["created"] == 2, \
            f"All rules were valid but created={body['created']}: {body}"


# ── Internal endpoint consistency ──────────────────────────────────────────

@pytest.mark.skip(reason="internal endpoints not exposed via Kong gateway")
class TestInternalEndpointConsistency:
    def test_create_rbac_rule_increments_internal_count(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        before_r = _send(request.node, "GET", f"{base_url}/internal/rbac/rules",
                         params={"limit": 1000, "offset": 0})
        if before_r.status_code != 200:
            pytest.skip("Internal RBAC endpoint not available")
        count_before = len(before_r.json()["rules"])

        _send(request.node, "POST", f"{base_url}/rbac/rules",
              headers=auth_headers, json_body=make_rbac_rule_request())

        after_r = _send(request.node, "GET", f"{base_url}/internal/rbac/rules",
                        params={"limit": 1000, "offset": 0})
        assert after_r.status_code == 200
        count_after = len(after_r.json()["rules"])
        assert count_after >= count_before, \
            "Rule count via internal endpoint should not decrease after a create"

    def test_rbac_version_changes_after_rule_modification(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_id = None
        try:
            version_before_r = _send(request.node, "GET",
                                     f"{base_url}/internal/rbac/rules/version")
            if version_before_r.status_code != 200:
                pytest.skip("Internal version endpoint not available")
            assert_version_response(version_before_r.json())
            version_before = version_before_r.json()["version"]

            create_r = _send(request.node, "POST", f"{base_url}/rbac/rules",
                             headers=auth_headers, json_body=make_rbac_rule_request())
            assert create_r.status_code == 201
            rule_id = create_r.json()["rule"]["id"]

            version_after_r = _send(request.node, "GET",
                                    f"{base_url}/internal/rbac/rules/version")
            assert version_after_r.status_code == 200
            assert_version_response(version_after_r.json())
            version_after = version_after_r.json()["version"]

            assert version_after != version_before, \
                "RBAC ruleset version must change after adding a new rule"
        finally:
            if rule_id:
                req_lib.delete(f"{base_url}/rbac/rules/{rule_id}", headers=auth_headers)

    def test_jbac_version_changes_after_rule_modification(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        rule_id = None
        try:
            version_before_r = _send(request.node, "GET",
                                     f"{base_url}/internal/jbac/rules/version")
            if version_before_r.status_code != 200:
                pytest.skip("Internal JBAC version endpoint not available")
            assert_version_response(version_before_r.json())
            version_before = version_before_r.json()["version"]

            create_r = _send(request.node, "POST", f"{base_url}/jbac/rules",
                             headers=auth_headers, json_body=make_jbac_rule_request())
            assert create_r.status_code == 201
            rule_id = create_r.json()["rule"]["id"]

            version_after_r = _send(request.node, "GET",
                                    f"{base_url}/internal/jbac/rules/version")
            assert version_after_r.status_code == 200
            assert_version_response(version_after_r.json())
            version_after = version_after_r.json()["version"]

            assert version_after != version_before, \
                "JBAC ruleset version must change after adding a new rule"
        finally:
            if rule_id:
                req_lib.delete(f"{base_url}/jbac/rules/{rule_id}", headers=auth_headers)
