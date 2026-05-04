import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_error_schema, assert_gateway_headers, assert_json_content_type
from tests.helpers.factories import (
    make_invalid_process_payload,
    make_invalid_transition_payload,
    make_process_payload,
)

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
        "details": {},
    },
}
ERROR_ARRAY_SCHEMA = {"type": "array", "items": SINGLE_ERROR_SCHEMA}


def _send(node, method, url, headers=None, json_body=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestProcessNegativeContracts:
    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/process",
                         headers=auth_headers,
                         json_body=make_invalid_process_payload("missing_required"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        if isinstance(body, list):
            assert_error_schema(body, ERROR_ARRAY_SCHEMA)

    def test_wrong_field_types_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/process",
                         headers=auth_headers,
                         json_body=make_invalid_process_payload("wrong_type"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/process")
        assert response.status_code == 401
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_bearer_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/process", headers=bad)
        assert response.status_code == 401
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_uuid_path_param_returns_4xx(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/process/!!!bad-id!!!", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestStateNegativeContracts:
    def test_create_state_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/process/{uuid.uuid4()}/state",
                         headers=auth_headers, json_body={})
        assert response.status_code in (400, 404)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_state_invalid_uuid_returns_4xx(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/state/not-a-uuid", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestActionNegativeContracts:
    def test_create_action_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/state/{uuid.uuid4()}/action",
                         headers=auth_headers, json_body={})
        assert response.status_code in (400, 404)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_action_invalid_uuid_returns_4xx(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/action/not-a-uuid", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestTransitionNegativeContracts:
    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/transition",
                         headers=auth_headers,
                         json_body=make_invalid_transition_payload("missing_required"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_entity_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/transition",
                         headers=auth_headers,
                         json_body=make_invalid_transition_payload("missing_entity_id"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_process_id_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/transition",
                         headers=auth_headers,
                         json_body=make_invalid_transition_payload("missing_process_id"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_unknown_process_id_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/transition",
                         headers=auth_headers,
                         json_body={"processId": str(uuid.uuid4()), "entityId": "entity-001"})
        assert response.status_code in (404, 400)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


class TestEscalationNegativeContracts:
    def test_create_escalation_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/process/{uuid.uuid4()}/escalation",
                         headers=auth_headers, json_body={})
        assert response.status_code in (400, 404)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_escalation_invalid_uuid_returns_4xx(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/escalation/not-a-uuid", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)
