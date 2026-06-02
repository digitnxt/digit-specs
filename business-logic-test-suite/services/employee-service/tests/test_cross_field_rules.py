"""
Cross-field rule tests for Employee service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _base_employee():
    return {
        "employeeType": "PERMANENT",
        "department": "IT",
        "designation": "Engineer",
        "jurisdictions": [{
            "boundaryRelation": [{
                "code": "STATE_001",
                "boundaryType": "STATE",
                "hierarchyType": "ADMIN",
            }],
        }],
    }


# ---------------------------------------------------------------------------
# BR-CF-001: Required core employee fields non-empty
# ---------------------------------------------------------------------------

class TestBR_CF_001_required_core_employee_fields_non_empty:
    """employeeType, department, designation are all mandatory."""

    def test_valid_employee_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [_base_employee()]})
        assert resp.status_code in (200, 201), f"Valid employee must be accepted: {resp.text}"

    def test_missing_employee_type_rejected(self, request, base_url, auth_headers):
        emp = {k: v for k, v in _base_employee().items() if k != "employeeType"}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code == 400, \
            f"Expected 400 for missing employeeType, got {resp.status_code}: {resp.text}"

    def test_missing_department_rejected(self, request, base_url, auth_headers):
        emp = {k: v for k, v in _base_employee().items() if k != "department"}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code == 400, \
            f"Expected 400 for missing department, got {resp.status_code}: {resp.text}"

    def test_missing_designation_rejected(self, request, base_url, auth_headers):
        emp = {k: v for k, v in _base_employee().items() if k != "designation"}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code == 400, \
            f"Expected 400 for missing designation, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: Employee type must be valid enum
# ---------------------------------------------------------------------------

class TestBR_CF_002_employee_type_must_be_valid_enum:
    """employeeType must be one of PERMANENT, CONTRACT, TEMPORARY."""

    def test_invalid_employee_type_rejected(self, request, base_url, auth_headers):
        emp = {**_base_employee(), "employeeType": "FREELANCE"}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code == 400, \
            f"Expected 400 for invalid employeeType, got {resp.status_code}: {resp.text}"

    def test_contract_type_accepted(self, request, base_url, auth_headers):
        emp = {**_base_employee(), "employeeType": "CONTRACT"}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code in (200, 201), \
            f"CONTRACT type must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Boundary relation array non-empty with complete elements
# ---------------------------------------------------------------------------

class TestBR_CF_003_boundary_relation_array_non_empty:
    """boundaryRelation must have at least one element with all three fields."""

    def test_empty_boundary_relation_rejected(self, request, base_url, auth_headers):
        emp = {**_base_employee(), "jurisdictions": [{"boundaryRelation": []}]}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code == 400, \
            f"Expected 400 for empty boundaryRelation, got {resp.status_code}: {resp.text}"

    def test_boundary_relation_missing_hierarchy_type_rejected(
        self, request, base_url, auth_headers
    ):
        emp = {**_base_employee(), "jurisdictions": [{
            "boundaryRelation": [{"code": "STATE_001", "boundaryType": "STATE"}],
        }]}
        resp = _post(request.node, f"{base_url}/employees", auth_headers,
                     {"employee": [emp]})
        assert resp.status_code == 400, \
            f"Expected 400 for missing hierarchyType, got {resp.status_code}: {resp.text}"
