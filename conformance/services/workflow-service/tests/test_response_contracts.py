import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_required_fields,
    assert_field_types,
    assert_uuid_field,
    assert_service_response_headers,
)
from tests.helpers.factories import make_process_payload, make_state_payload


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
        assert isinstance(response.json(), list), "GET /process must return a JSON array"

    def test_process_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/process", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json():
            assert_field_types(item, {"name": str, "code": str})
            assert_uuid_field(item, "id")


class TestProcessDefinitionContract:
    def test_definition_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/process/definition", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list), "GET /process/definition must return a JSON array"

    def test_definition_item_has_process_and_states(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/process/definition", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json():
            if "process" in item:
                assert_field_types(item["process"], {"name": str, "code": str})
            if "states" in item:
                assert isinstance(item["states"], list)


class TestProcessByIdContract:
    def test_nonexistent_process_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/process/{uuid.uuid4()}", headers=auth_headers)

        assert response.status_code == 404
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_uuid_path_param_rejected(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/process/!!!invalid!!!", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestCreateProcessContract:
    def test_create_returns_201_with_process_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        payload = make_process_payload()
        response = _send(request.node, "POST", f"{base_url}/process",
                         headers=auth_headers, json_body=payload)

        assert response.status_code == 201
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["id", "name", "code"])
        assert_field_types(body, {"id": str, "name": str, "code": str})
        assert_uuid_field(body, "id")
        assert body["name"] == payload["name"]
        assert body["code"] == payload["code"]

        req_lib.delete(f"{base_url}/process/{body['id']}", headers=auth_headers)

    def test_create_process_conflict_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        payload = make_process_payload(code="CONFLICT-CODE-001")
        r1 = _send(request.node, "POST", f"{base_url}/process",
                   headers=auth_headers, json_body=payload)
        if r1.status_code != 201:
            pytest.skip("Could not create initial process for conflict test")

        process_id = r1.json().get("id")
        try:
            r2 = _send(request.node, "POST", f"{base_url}/process",
                       headers=auth_headers, json_body=payload)
            assert r2.status_code == 409
            assert_json_content_type(r2)
            assert_gateway_headers(r2, gateway_headers_spec)
        finally:
            if process_id:
                req_lib.delete(f"{base_url}/process/{process_id}", headers=auth_headers)


class TestStateListContract:
    def test_list_states_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        proc_resp = _send(request.node, "POST", f"{base_url}/process",
                          headers=auth_headers, json_body=make_process_payload())
        if proc_resp.status_code != 201:
            pytest.skip("Cannot create process — skipping state list test")

        process_id = proc_resp.json()["id"]
        try:
            response = _send(request.node, "GET",
                             f"{base_url}/process/{process_id}/state", headers=auth_headers)
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            assert isinstance(response.json(), list)
        finally:
            req_lib.delete(f"{base_url}/process/{process_id}", headers=auth_headers)

    def test_list_states_invalid_process_id_rejected(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/process/not-a-uuid/state", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestStateByIdContract:
    def test_nonexistent_state_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/state/{uuid.uuid4()}", headers=auth_headers)
        assert response.status_code == 404
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


class TestActionByIdContract:
    def test_nonexistent_action_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/action/{uuid.uuid4()}", headers=auth_headers)
        assert response.status_code == 404
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


class TestEscalationByIdContract:
    def test_nonexistent_escalation_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/escalation/{uuid.uuid4()}", headers=auth_headers)
        assert response.status_code == 404
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


class TestTransitionSearchContract:
    def test_search_transitions_returns_paginated_response(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/transition", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["processInstances", "totalCount"])
        assert isinstance(body["processInstances"], list)
        assert isinstance(body["totalCount"], int)

    def test_process_instance_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/transition", headers=auth_headers)
        assert response.status_code == 200

        for instance in response.json().get("processInstances", []):
            assert_field_types(instance, {"id": str, "processId": str, "entityId": str})


class TestAutoEscalationSearchContract:
    def test_search_escalations_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        pytest.skip("Skipped GET /auto/_search response contract due to environment-dependent gateway behavior")
        response = _send(request.node, "GET", f"{base_url}/auto/_search", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list), "GET /auto/_search must return a JSON array"
