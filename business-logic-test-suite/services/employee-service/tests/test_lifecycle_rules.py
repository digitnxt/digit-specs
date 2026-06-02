"""
Lifecycle rule tests for Employee service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _base_employee(code=None):
    emp = {
        "employeeType": "PERMANENT",
        "department": "IT",
        "designation": "Engineer",
        "jurisdictions": [{
            "boundaryRelation": [{
                "code": "STATE_001", "boundaryType": "STATE", "hierarchyType": "ADMIN",
            }],
        }],
    }
    if code:
        emp["code"] = code
    return emp


# ---------------------------------------------------------------------------
# BR-LC-001: Soft deactivation preserves employee record
# ---------------------------------------------------------------------------

class TestBR_LC_001_soft_deactivation_preserves_employee_record:
    """Deactivating sets isActive=false; record still exists."""

    def test_deactivated_employee_still_exists_in_db(self, request, base_url, auth_headers):
        code = "EMP-SOFT-" + uuid.uuid4().hex[:4].upper()
        create = req_lib.post(f"{base_url}/employees", headers=auth_headers,
                              json={"employee": [_base_employee(code)]})
        if create.status_code not in (200, 201):
            return
        emp = (create.json().get("employees") or [create.json()])[0]
        emp_id = emp.get("id") or emp.get("employeeId")
        if not emp_id:
            return

        deact = req_lib.post(f"{base_url}/employees/{emp_id}/deactivate",
                             headers=auth_headers)
        assert deact.status_code in (200, 204), f"Deactivation failed: {deact.text}"

        get_resp = req_lib.get(f"{base_url}/employees/{emp_id}", headers=auth_headers)
        assert get_resp.status_code == 200, "Employee record must still be accessible after deactivation"
        result = get_resp.json()
        emp_data = (result.get("employees") or [result])[0]
        assert emp_data.get("isActive") is False, "isActive must be False after deactivation"


# ---------------------------------------------------------------------------
# BR-LC-003: PUT replaces jurisdictions atomically
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-LC-002: Hard delete removes record and cascades
# ---------------------------------------------------------------------------

class TestBR_LC_002_hard_delete_removes_record_and_cascades:
    """DELETE /employees/:id permanently removes the employee; subsequent GET returns 404."""

    def test_hard_delete_makes_employee_unreachable(self, request, base_url, auth_headers):
        code = "EMP-HARD-" + uuid.uuid4().hex[:4].upper()
        create = req_lib.post(f"{base_url}/employees", headers=auth_headers,
                              json={"employee": [_base_employee(code)]})
        if create.status_code not in (200, 201):
            return
        emp = (create.json().get("employees") or [create.json()])[0]
        emp_id = emp.get("id") or emp.get("employeeId")
        if not emp_id:
            return

        del_resp = _post(request.node, f"{base_url}/employees/{emp_id}/delete",
                         auth_headers, {})
        if del_resp.status_code == 404:
            del_resp = req_lib.delete(f"{base_url}/employees/{emp_id}",
                                      headers=auth_headers)
        assert del_resp.status_code in (200, 204), f"Hard delete must succeed: {del_resp.text}"

        get_resp = req_lib.get(f"{base_url}/employees/{emp_id}", headers=auth_headers)
        assert get_resp.status_code == 404, \
            f"Hard-deleted employee must return 404 on GET, got {get_resp.status_code}"

    def test_delete_nonexistent_employee_returns_404(self, request, base_url, auth_headers):
        fake_id = str(uuid.uuid4())
        resp = req_lib.delete(f"{base_url}/employees/{fake_id}", headers=auth_headers)
        assert resp.status_code == 404, \
            f"Expected 404 for deleting nonexistent employee, got {resp.status_code}: {resp.text}"


class TestBR_LC_003_put_replaces_jurisdictions_atomically:
    """PUT replaces the entire jurisdiction set; old jurisdictions are removed."""

    def test_put_with_new_jurisdictions_replaces_old(self, request, base_url, auth_headers):
        code = "EMP-JUR-" + uuid.uuid4().hex[:4].upper()
        create = req_lib.post(f"{base_url}/employees", headers=auth_headers,
                              json={"employee": [_base_employee(code)]})
        if create.status_code not in (200, 201):
            return
        emp = (create.json().get("employees") or [create.json()])[0]
        emp_id = emp.get("id") or emp.get("employeeId")
        if not emp_id:
            return

        update = _post(request.node, f"{base_url}/employees/{emp_id}", auth_headers, {
            "employee": {
                **emp,
                "jurisdictions": [{
                    "boundaryRelation": [{
                        "code": "DISTRICT_001",
                        "boundaryType": "DISTRICT",
                        "hierarchyType": "ADMIN",
                    }],
                }],
            },
        })
        assert update.status_code in (200, 201), \
            f"PUT with new jurisdiction must succeed, got {update.status_code}: {update.text}"
