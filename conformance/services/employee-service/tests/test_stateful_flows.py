import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import make_employee, make_employee_update, make_employee_patch, make_jurisdiction
from tests.helpers.validators import assert_gateway_headers, assert_required_fields


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
            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[make_employee()])
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            emp_id = r.json()[0]["id"]

            # 2. GET by ID
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == emp_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH
            r = _send(request.node, "GET", f"{base_url}/employees",
                      headers=auth_headers)
            assert r.status_code == 200
            employees = r.json()["employees"]
            assert any(e["id"] == emp_id for e in employees)

            # 4. PUT (full update)
            r = _send(request.node, "PUT", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers, json_body=make_employee_update())
            assert r.status_code == 200, f"PUT failed: {r.text}"
            assert r.json()["id"] == emp_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 5. PATCH (partial update)
            r = _send(request.node, "PATCH", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers, json_body=make_employee_patch(employeeType="TEMPORARY"))
            assert r.status_code == 200, f"PATCH failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            # 6. DELETE
            r = _send(request.node, "DELETE", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers)
            assert r.status_code == 200, f"Delete failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)
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

            # DEACTIVATE
            r = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/deactivate",
                      headers=auth_headers)
            assert r.status_code in (200, 422), f"Deactivate failed: {r.text}"
            if r.status_code == 200:
                assert r.json()["id"] == emp_id
                assert r.json().get("isActive") is False
                assert_gateway_headers(r, gateway_headers_spec)

                # REACTIVATE
                r = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/reactivate",
                          headers=auth_headers)
                assert r.status_code in (200, 422), f"Reactivate failed: {r.text}"
                if r.status_code == 200:
                    assert r.json()["id"] == emp_id
                    assert r.json().get("isActive") is True
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
            assert len(r.json()) == 3
            emp_ids = [e["id"] for e in r.json()]
        finally:
            for eid in emp_ids:
                _cleanup(f"{base_url}/employees/{eid}", auth_headers)


class TestJurisdictionLifecycle:
    def test_create_read_update_jurisdiction(self, request, base_url, auth_headers, gateway_headers_spec):
        emp_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[make_employee()])
            assert r.status_code == 201, f"Employee create failed: {r.text}"
            emp_id = r.json()[0]["id"]

            # 1. CREATE jurisdiction
            r = _send(request.node, "POST", f"{base_url}/jurisdictions",
                      headers=auth_headers,
                      json_body=make_jurisdiction(emp_id))
            assert r.status_code == 201, f"Jurisdiction create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_required_fields(r.json(), ["id", "employeeId", "boundaryRelation", "isActive"])
            juris_id = r.json()["id"]

            # 2. GET jurisdiction by UUID
            r = _send(request.node, "GET", f"{base_url}/jurisdictions/{juris_id}",
                      headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == juris_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH jurisdictions
            r = _send(request.node, "GET", f"{base_url}/jurisdictions",
                      headers=auth_headers)
            assert r.status_code == 200
            assert any(j["id"] == juris_id for j in r.json()["jurisdictions"])

            # 4. PUT (replace) jurisdiction
            r = _send(request.node, "PUT", f"{base_url}/jurisdictions/{juris_id}",
                      headers=auth_headers,
                      json_body={"employeeId": emp_id,
                                  "boundaryRelation": ["BOUNDARY-UPDATED"],
                                  "isActive": True})
            assert r.status_code == 200, f"Jurisdiction PUT failed: {r.text}"
            assert r.json()["id"] == juris_id
            assert_gateway_headers(r, gateway_headers_spec)

        finally:
            if emp_id:
                _cleanup(f"{base_url}/employees/{emp_id}", auth_headers)


class TestEmployeeWithJurisdictionFlow:
    def test_full_employee_jurisdiction_lifecycle(self, request, base_url, auth_headers, gateway_headers_spec):
        emp_id = None
        try:
            # Create employee
            r = _send(request.node, "POST", f"{base_url}/employees",
                      headers=auth_headers, json_body=[make_employee()])
            assert r.status_code == 201
            emp_id = r.json()[0]["id"]

            # Create jurisdiction for that employee
            r = _send(request.node, "POST", f"{base_url}/jurisdictions",
                      headers=auth_headers,
                      json_body=make_jurisdiction(emp_id, ["BOUNDARY-A", "BOUNDARY-B"]))
            assert r.status_code == 201
            juris_id = r.json()["id"]

            # Employee GET should include jurisdiction in jurisdictions array (if eager-loaded)
            r = _send(request.node, "GET", f"{base_url}/employees/{emp_id}",
                      headers=auth_headers)
            assert r.status_code == 200
            body = r.json()
            if "jurisdictions" in body and body["jurisdictions"]:
                assert any(j["id"] == juris_id for j in body["jurisdictions"]), \
                    "Created jurisdiction not found in employee jurisdictions"

        finally:
            if emp_id:
                _cleanup(f"{base_url}/employees/{emp_id}", auth_headers)
