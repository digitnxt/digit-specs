import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_service_response_headers,
    assert_required_fields,
    assert_field_types,
    assert_bare_array,
    assert_boundary_relation,
)
from tests.helpers.factories import make_employee, make_jurisdiction_create


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _create_employee(node, base_url, auth_headers, emp=None):
    """Create one employee, returning the created object, or None on failure."""
    r = _send(node, "POST", f"{base_url}/employees",
              headers=auth_headers, json_body=[emp or make_employee()])
    if r.status_code != 201:
        return None
    return r.json()[0]


def _delete_employee(base_url, auth_headers, emp_id):
    try:
        req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)
    except Exception:
        pass


class TestEmployeeCreateContract:
    def test_create_returns_201_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[make_employee()])
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert isinstance(body, list) and len(body) == 1
        item = body[0]
        assert_required_fields(item, ["id", "isActive", "version"])
        assert_field_types(item, {"id": str, "isActive": bool, "version": int})
        assert item["version"] == 1, "A freshly created employee must start at version 1"

        _delete_employee(base_url, auth_headers, item["id"])

    def test_create_employee_has_expected_fields(self, request, base_url, auth_headers, gateway_headers_spec):
        emp = make_employee()
        item = _create_employee(request.node, base_url, auth_headers, emp)
        assert item is not None, "Employee create failed"
        try:
            assert_field_types(item, {"code": str, "employeeType": str, "department": str,
                                       "designation": str, "isActive": bool})
            # code is server-managed (auto-generated when omitted) and must come back.
            assert item.get("code"), "Created employee must carry a server-assigned code"
            assert item["employeeType"] == emp["employeeType"]
            assert item["department"] == emp["department"]
            assert item["designation"] == emp["designation"]
        finally:
            _delete_employee(base_url, auth_headers, item["id"])


class TestEmployeeSearchContract:
    def test_search_returns_bare_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_bare_array(response.json())

    def test_search_items_have_employee_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        item = _create_employee(request.node, base_url, auth_headers)
        assert item is not None, "Employee create failed"
        try:
            response = _send(request.node, "GET", f"{base_url}/employees", headers=auth_headers)
            assert response.status_code == 200
            employees = assert_bare_array(response.json())
            match = next((e for e in employees if e["id"] == item["id"]), None)
            assert match is not None, "Created employee not found in search results"
            assert_field_types(match, {"id": str, "isActive": bool, "version": int})
        finally:
            _delete_employee(base_url, auth_headers, item["id"])

    def test_search_filter_by_code(self, request, base_url, auth_headers, gateway_headers_spec):
        item = _create_employee(request.node, base_url, auth_headers)
        if item is None:
            pytest.skip("Could not create employee for filter test")
        emp_code = item.get("code", "")
        try:
            if emp_code:
                # Filter param is `codes` (plural, IN-match) per the spec.
                response = _send(request.node, "GET", f"{base_url}/employees",
                                 headers=auth_headers, params={"codes": emp_code})
                assert response.status_code == 200
                employees = assert_bare_array(response.json())
                assert any(e.get("code") == emp_code for e in employees)
        finally:
            _delete_employee(base_url, auth_headers, item["id"])


class TestEmployeeGetByIdContract:
    def test_get_by_id_returns_employee(self, request, base_url, auth_headers, gateway_headers_spec):
        item = _create_employee(request.node, base_url, auth_headers)
        if item is None:
            pytest.skip("Could not create employee")
        emp_id = item["id"]
        try:
            response = _send(request.node, "GET", f"{base_url}/employees/{emp_id}",
                             headers=auth_headers)
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert_required_fields(body, ["id", "isActive", "version"])
            assert body["id"] == emp_id
        finally:
            _delete_employee(base_url, auth_headers, emp_id)

    def test_get_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestJurisdictionCreateContract:
    def test_create_jurisdiction_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        item = _create_employee(request.node, base_url, auth_headers)
        if item is None:
            pytest.skip("Could not create employee for jurisdiction test")
        emp_id = item["id"]
        try:
            response = _send(request.node, "POST", f"{base_url}/employees/{emp_id}/jurisdictions",
                             headers=auth_headers, json_body=make_jurisdiction_create())
            if response.status_code == 400:
                pytest.skip("Boundary service enabled and rejected the sample boundary codes")
            assert response.status_code == 201
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert_required_fields(body, ["id", "employeeId", "boundaryRelation", "isActive", "version"])
            assert_field_types(body, {"id": str, "employeeId": str, "isActive": bool, "version": int})
            assert body["employeeId"] == emp_id
            assert_boundary_relation(body["boundaryRelation"])
        finally:
            _delete_employee(base_url, auth_headers, emp_id)


class TestJurisdictionSearchContract:
    def test_search_jurisdictions_returns_bare_array(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        item = _create_employee(request.node, base_url, auth_headers)
        if item is None:
            pytest.skip("Could not create employee for jurisdiction search")
        emp_id = item["id"]
        try:
            response = _send(request.node, "GET", f"{base_url}/employees/{emp_id}/jurisdictions",
                             headers=auth_headers)
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            assert_bare_array(response.json())
        finally:
            _delete_employee(base_url, auth_headers, emp_id)

    def test_get_nonexistent_jurisdiction_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        item = _create_employee(request.node, base_url, auth_headers)
        if item is None:
            pytest.skip("Could not create employee for jurisdiction lookup")
        emp_id = item["id"]
        try:
            response = _send(request.node, "GET",
                             f"{base_url}/employees/{emp_id}/jurisdictions/{uuid.uuid4()}",
                             headers=auth_headers)
            assert response.status_code == 404
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _delete_employee(base_url, auth_headers, emp_id)
