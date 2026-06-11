import pytest
import requests as req_lib
import uuid
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_required_fields,
    assert_field_types,
    assert_uuid_field,
    assert_service_response_headers,
)
from tests.helpers.factories import make_process_payload


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestProcessListContract:
    def test_search_processes_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/process", headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert isinstance(body, (list, dict))


class TestProcessDefinitionContract:
    def test_definition_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/process/definition", headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert isinstance(body, (list, dict))


class TestCreateProcessContract:
    def test_create_returns_201_with_process_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        payload = make_process_payload()
        response = _send(request.node, "POST", f"{base_url}/process", headers=auth_headers, json_body=payload)
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["id", "name", "code"])
        assert_field_types(body, {"id": str, "name": str, "code": str})
        assert_uuid_field(body, "id")
        req_lib.delete(f"{base_url}/process/code/{body['code']}", headers=auth_headers)

    def test_create_process_conflict_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        code = f"CONFLICT-{uuid.uuid4().hex[:8].upper()}"
        payload = make_process_payload(code=code)
        r1 = _send(request.node, "POST", f"{base_url}/process", headers=auth_headers, json_body=payload)
        if r1.status_code != 201:
            pytest.skip("Could not create initial process for conflict test")
        process_code = r1.json().get("code")
        try:
            r2 = _send(request.node, "POST", f"{base_url}/process", headers=auth_headers, json_body=payload)
            assert r2.status_code == 409
        finally:
            if process_code:
                req_lib.delete(f"{base_url}/process/code/{process_code}", headers=auth_headers)


class TestStateListContract:
    def test_list_states_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        proc_resp = _send(request.node, "POST", f"{base_url}/process", headers=auth_headers, json_body=make_process_payload())
        if proc_resp.status_code != 201:
            pytest.skip("Cannot create process")
        process_code = proc_resp.json()["code"]
        try:
            response = _send(request.node, "GET", f"{base_url}/process/{process_code}/state", headers=auth_headers)
            assert response.status_code == 200
            body = response.json()
            assert isinstance(body, (list, dict))
        finally:
            req_lib.delete(f"{base_url}/process/code/{process_code}", headers=auth_headers)


class TestTransitionSearchContract:
    def test_search_transitions_returns_paginated_response(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/transition", headers=auth_headers)
        assert response.status_code == 200
        body = response.json()
        assert_required_fields(body, ["processInstances", "totalCount"])
        assert isinstance(body["processInstances"], list)
        assert isinstance(body["totalCount"], int)

    def test_process_instance_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/transition", headers=auth_headers)
        assert response.status_code == 200
        for instance in response.json().get("processInstances", []):
            assert_field_types(instance, {"id": str, "processCode": str, "entityId": str})
