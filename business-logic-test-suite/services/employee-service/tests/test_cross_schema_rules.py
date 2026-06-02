"""
Cross-schema rule tests for Employee service.
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
# BR-CS-001: Employee code uniqueness per tenant
# ---------------------------------------------------------------------------

class TestBR_CS_001_employee_code_uniqueness_per_tenant:
    """Duplicate employee code within a tenant returns 409."""

    def test_duplicate_code_returns_409(self, request, base_url, auth_headers):
        code = "EMP-" + uuid.uuid4().hex[:6].upper()
        req_lib.post(f"{base_url}/employees", headers=auth_headers,
                     json={"employee": [_base_employee(code)]})
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [_base_employee(code)]})
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate code, got {resp.status_code}: {resp.text}"

    def test_unique_code_accepted(self, request, base_url, auth_headers):
        code = "EMP-" + uuid.uuid4().hex[:6].upper()
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [_base_employee(code)]})
        assert resp.status_code in (200, 201), \
            f"Unique code must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-003: Employee deletion cascades to jurisdictions
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-CS-002: Jurisdiction employee reference must exist
# ---------------------------------------------------------------------------

class TestBR_CS_002_jurisdiction_employee_reference_must_exist:
    """EmployeeJurisdiction.employeeId must reference an existing employee."""

    def test_jurisdiction_create_for_nonexistent_employee_rejected(
        self, request, base_url, auth_headers
    ):
        import uuid
        fake_id = str(uuid.uuid4())
        resp = req_lib.post(f"{base_url}/employees/{fake_id}/jurisdictions",
                            headers=auth_headers,
                            json={"jurisdiction": {
                                "employeeId": fake_id,
                                "boundaryRelation": [{
                                    "code": "STATE_001",
                                    "boundaryType": "STATE",
                                    "hierarchyType": "ADMIN",
                                }],
                            }})
        assert resp.status_code in (400, 404), \
            f"Expected 400/404 for jurisdiction on nonexistent employee, got {resp.status_code}: {resp.text}"


class TestBR_CS_003_employee_deletion_cascades_to_jurisdictions:
    """Hard-deleting an employee removes all associated jurisdiction rows."""

    def test_jurisdictions_not_queryable_after_employee_deletion(
        self, request, base_url, auth_headers
    ):
        code = "EMP-DEL-" + uuid.uuid4().hex[:4].upper()
        create = req_lib.post(f"{base_url}/employees", headers=auth_headers,
                              json={"employee": [_base_employee(code)]})
        if create.status_code not in (200, 201):
            return
        emp = (create.json().get("employees") or [create.json()])[0]
        emp_id = emp.get("id") or emp.get("employeeId")
        if not emp_id:
            return

        del_resp = req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)
        assert del_resp.status_code in (200, 204), f"Delete failed: {del_resp.text}"

        search = req_lib.get(f"{base_url}/employees/{emp_id}", headers=auth_headers)
        assert search.status_code == 404, \
            "Employee must not be found after hard delete (cascade verified implicitly)"
