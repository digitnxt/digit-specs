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
    assert_pagination_shape,
)
from tests.helpers.factories import make_employee, make_jurisdiction


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestEmployeeCreateContract:
    def test_create_returns_201_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[make_employee()])
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert isinstance(body, list) and len(body) >= 1
        assert_required_fields(body[0], ["id", "isActive"])
        assert_field_types(body[0], {"id": str, "isActive": bool})

        req_lib.delete(f"{base_url}/employees/{body[0]['id']}", headers=auth_headers)

    def test_create_employee_has_expected_fields(self, request, base_url, auth_headers, gateway_headers_spec):
        emp = make_employee()
        response = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[emp])
        assert response.status_code == 201
        item = response.json()[0]
        assert_field_types(item, {"code": str, "employeeType": str, "department": str,
                                   "designation": str, "isActive": bool})

        req_lib.delete(f"{base_url}/employees/{item['id']}", headers=auth_headers)


class TestEmployeeSearchContract:
    def test_search_returns_paginated_response(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_pagination_shape(response.json(), "employees")

    def test_search_pagination_defaults(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees",
                         headers=auth_headers)
        assert response.status_code == 200
        body = response.json()
        assert body["page"] >= 1
        assert 1 <= body["size"] <= 100

    def test_search_filter_by_code(self, request, base_url, auth_headers, gateway_headers_spec):
        emp = make_employee()
        create_r = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[emp])
        if create_r.status_code != 201:
            pytest.skip("Could not create employee for filter test")
        emp_id = create_r.json()[0]["id"]
        emp_code = create_r.json()[0].get("code", "")
        try:
            if emp_code:
                response = _send(request.node, "GET", f"{base_url}/employees",
                                 headers=auth_headers, params={"code": emp_code})
                assert response.status_code == 200
                employees = response.json()["employees"]
                assert any(e.get("code") == emp_code for e in employees)
        finally:
            req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)


class TestEmployeeGetByIdContract:
    def test_get_by_id_returns_employee(self, request, base_url, auth_headers, gateway_headers_spec):
        create_r = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[make_employee()])
        if create_r.status_code != 201:
            pytest.skip("Could not create employee")
        emp_id = create_r.json()[0]["id"]
        try:
            response = _send(request.node, "GET", f"{base_url}/employees/{emp_id}",
                             headers=auth_headers)
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert_required_fields(body, ["id", "isActive"])
            assert body["id"] == emp_id
        finally:
            req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)

    def test_get_nonexistent_employee_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/employees/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestJurisdictionCreateContract:
    def test_create_jurisdiction_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        create_r = _send(request.node, "POST", f"{base_url}/employees",
                         headers=auth_headers, json_body=[make_employee()])
        if create_r.status_code != 201:
            pytest.skip("Could not create employee for jurisdiction test")
        emp_id = create_r.json()[0]["id"]
        try:
            response = _send(request.node, "POST", f"{base_url}/jurisdictions",
                             headers=auth_headers,
                             json_body=make_jurisdiction(emp_id))
            assert response.status_code == 201
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert_required_fields(body, ["id", "employeeId", "boundaryRelation", "isActive", "tenantId"])
            assert_field_types(body, {"id": str, "employeeId": str, "isActive": bool})
            assert isinstance(body["boundaryRelation"], list)
        finally:
            req_lib.delete(f"{base_url}/employees/{emp_id}", headers=auth_headers)


class TestJurisdictionSearchContract:
    def test_search_jurisdictions_returns_paginated_response(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/jurisdictions",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_pagination_shape(response.json(), "jurisdictions")

    def test_get_nonexistent_jurisdiction_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/jurisdictions/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
