import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_error_schema, assert_gateway_headers, assert_json_content_type
from tests.helpers.factories import make_invalid_employee, make_invalid_jurisdiction, make_employee

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}
ERROR_ARRAY_SCHEMA = {"type": "array", "items": SINGLE_ERROR_SCHEMA}


def _send(node, method, url, headers=None, json_body=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestEmployeeNegativeContracts:
    def test_empty_array_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers,
                         json_body=[make_invalid_employee("missing_required")])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_employee_type_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers,
                         json_body=[make_invalid_employee("missing_employee_type")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_department_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers,
                         json_body=[make_invalid_employee("missing_department")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_designation_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers,
                         json_body=[make_invalid_employee("missing_designation")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/employees", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT", f"{base_url}/employees/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body={"employeeType": "PERMANENT", "isActive": True})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_patch_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PATCH", f"{base_url}/employees/{uuid.uuid4()}",
                         headers=auth_headers, json_body={"isActive": True})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "DELETE", f"{base_url}/employees/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_deactivate_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/employees/{uuid.uuid4()}/deactivate",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_reactivate_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/employees/{uuid.uuid4()}/reactivate",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestJurisdictionNegativeContracts:
    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/jurisdictions",
                         headers=auth_headers,
                         json_body=make_invalid_jurisdiction("missing_required"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_employee_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/jurisdictions",
                         headers=auth_headers,
                         json_body=make_invalid_jurisdiction("missing_employee_id"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_boundary_relation_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/jurisdictions",
                         headers=auth_headers,
                         json_body=make_invalid_jurisdiction("missing_boundary_relation"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_empty_boundary_relation_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/jurisdictions",
                         headers=auth_headers,
                         json_body=make_invalid_jurisdiction("empty_boundary_relation"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_nonexistent_jurisdiction_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/jurisdictions/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_replace_nonexistent_jurisdiction_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT", f"{base_url}/jurisdictions/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body={"employeeId": str(uuid.uuid4()),
                                    "boundaryRelation": ["BOUND-X"], "isActive": True})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
