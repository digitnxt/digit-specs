import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_employee,
    make_employee_update,
    make_employee_patch,
    make_jurisdiction_create,
    make_jurisdiction_update,
    make_boundary_relation,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_required_fields,
    assert_bare_array,
    assert_boundary_relation,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(url, headers):
    try:
        req_lib.delete(url, headers=headers)
    except Exception:
        pass


class TestEmployeeLifecycle:
    def test_create_read_update_patch_delete(self, request, base_url, auth_headers, gateway_headers_spec):
        emp_id = None
        try:
            # 1. CREATE — starts at version 1
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[make_employee()])
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            created = r.json()[0]
            emp_id = created["id"]
            version = created["version"]
            assert version == 1

            # 2. GET by ID
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == emp_id
            assert r.json()["version"] == version

            # 3. SEARCH (bare array)
            r = _send(request.node, "GET", f"{base_url}/employees", headers=auth_headers)
            assert r.status_code == 200
            assert any(e["id"] == emp_id for e in assert_bare_array(r.json()))

            # 4. PUT (full-state update) with the current version → bumps it
            r = _send(request.node, "PUT", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers, json_body=make_employee_update(version=version))
            assert r.status_code == 200, f"PUT failed: {r.text}"
            put_body = r.json()
            assert put_body["id"] == emp_id
            assert put_body["version"] == version + 1, "PUT must bump the version by 1"
            version = put_body["version"]
            assert_gateway_headers(r, gateway_headers_spec)

            # 5. PATCH (partial update) with the new version → bumps again
            r = _send(request.node, "PATCH", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers, json_body=make_employee_patch(version=version, employeeType="TEMPORARY"))
            assert r.status_code == 200, f"PATCH failed: {r.text}"
            patch_body = r.json()
            assert patch_body["version"] == version + 1, "PATCH must bump the version by 1"
            assert patch_body["employeeType"] == "TEMPORARY"
            assert_gateway_headers(r, gateway_headers_spec)

            # 6. DELETE → 204 No Content, empty body
            r = _send(request.node, "DELETE", f"{base_url}/employees/{emp_id}", headers=auth_headers)
            assert r.status_code == 204, f"Delete must return 204, got {r.status_code}: {r.text}"
            assert not r.content, "204 response must have an empty body"
            assert_gateway_headers(r, gateway_headers_spec)

            # 7. Confirm gone
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}", headers=auth_headers)
            assert r.status_code == 404
            emp_id = None

        finally:
            if emp_id:
                _cleanup(f"{base_url}/employees/{emp_id}", auth_headers)

    def test_deactivate_and_reactivate(self, request, base_url, auth_headers, gateway_headers_spec):
        emp_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[make_employee()])
            assert r.status_code == 201, f"Create failed: {r.text}"
            emp_id = r.json()[0]["id"]

            # DEACTIVATE → isActive false, version bumped
            r = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/deactivate", headers=auth_headers)
            assert r.status_code == 200, f"Deactivate failed: {r.text}"
            body = r.json()
            assert body["id"] == emp_id
            assert body["isActive"] is False
            assert_gateway_headers(r, gateway_headers_spec)

            # REACTIVATE → isActive true again
            r = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/reactivate", headers=auth_headers)
            assert r.status_code == 200, f"Reactivate failed: {r.text}"
            body = r.json()
            assert body["id"] == emp_id
            assert body["isActive"] is True
            assert_gateway_headers(r, gateway_headers_spec)

        finally:
            if emp_id:
                _cleanup(f"{base_url}/employees/{emp_id}", auth_headers)

    def test_create_multiple_employees(self, request, base_url, auth_headers, gateway_headers_spec):
        emp_ids = []
        try:
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers,
                      json_body=[make_employee(), make_employee(), make_employee()])
            assert r.status_code == 201, f"Bulk create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert len(body) == 3
            emp_ids = [e["id"] for e in body]
        finally:
            for eid in emp_ids:
                _cleanup(f"{base_url}/employees/{eid}", auth_headers)


class TestJurisdictionLifecycle:
    def test_create_read_search_update_jurisdiction(self, request, base_url, auth_headers, gateway_headers_spec):
        emp_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[make_employee()])
            assert r.status_code == 201, f"Employee create failed: {r.text}"
            emp_id = r.json()[0]["id"]

            # 1. CREATE jurisdiction (nested under the employee)
            r = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/jurisdictions",
                      headers=auth_headers, json_body=make_jurisdiction_create())
            if r.status_code == 400:
                pytest.skip("Boundary service enabled and rejected the sample boundary codes")
            assert r.status_code == 201, f"Jurisdiction create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            juris = r.json()
            assert_required_fields(juris, ["id", "employeeId", "boundaryRelation", "isActive", "version"])
            assert juris["employeeId"] == emp_id
            assert_boundary_relation(juris["boundaryRelation"])
            juris_id = juris["id"]
            juris_version = juris["version"]

            # 2. GET jurisdiction by UUID under the owning employee
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}/jurisdictions/{juris_id}",
                      headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == juris_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH jurisdictions (bare array)
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}/jurisdictions", headers=auth_headers)
            assert r.status_code == 200
            assert any(j["id"] == juris_id for j in assert_bare_array(r.json()))

            # 4. PUT (replace) jurisdiction with its version → bumps it
            r = _send(request.node, "PUT", f"{base_url}/employees/{emp_id}/jurisdictions/{juris_id}",
                      headers=auth_headers,
                      json_body=make_jurisdiction_update(
                          version=juris_version,
                          boundary_relations=[make_boundary_relation(code="WARD-UPDATED-001")]))
            # Replacement boundary code may fail boundary validation in a strict env.
            if r.status_code == 400:
                pytest.skip("Boundary service rejected the replacement boundary code")
            assert r.status_code == 200, f"Jurisdiction PUT failed: {r.text}"
            assert r.json()["id"] == juris_id
            assert r.json()["version"] == juris_version + 1
            assert_gateway_headers(r, gateway_headers_spec)

        finally:
            if emp_id:
                _cleanup(f"{base_url}/employees/{emp_id}", auth_headers)


class TestEmployeeWithInlineJurisdictionFlow:
    def test_create_employee_with_nested_jurisdiction(self, request, base_url, auth_headers, gateway_headers_spec):
        """Employee create accepts inline jurisdictions and returns them eager-loaded."""
        emp_id = None
        try:
            emp = make_employee(jurisdictions=[make_jurisdiction_create(
                boundary_relations=[make_boundary_relation(code="WARD-INLINE-001")])])
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[emp])
            if r.status_code == 400:
                pytest.skip("Boundary service enabled and rejected the inline boundary codes")
            assert r.status_code == 201, f"Create with nested jurisdiction failed: {r.text}"
            body = r.json()[0]
            emp_id = body["id"]

            # The created employee should carry the jurisdiction inline.
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}", headers=auth_headers)
            assert r.status_code == 200
            emp_body = r.json()
            assert emp_body.get("jurisdictions"), "Inline jurisdiction not returned on the employee"
            for j in emp_body["jurisdictions"]:
                assert j["employeeId"] == emp_id
                assert_boundary_relation(j["boundaryRelation"])

        finally:
            if emp_id:
                _cleanup(f"{base_url}/employees/{emp_id}", auth_headers)
