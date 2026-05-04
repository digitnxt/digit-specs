import pytest
import requests
from tests.helpers.validators import assert_error_schema, assert_gateway_headers, assert_json_content_type
from tests.helpers.factories import (
    make_invalid_process_payload,
    make_invalid_transition_payload,
    make_process_payload,
)

# Derived from common.yaml Error schema referenced in workflow.yaml
SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
        "details": {},
    },
}

# Some endpoints return array of errors on 400
ERROR_ARRAY_SCHEMA = {
    "type": "array",
    "items": SINGLE_ERROR_SCHEMA,
}


class TestProcessNegativeContracts:
    def test_missing_required_fields_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/process",
            json=make_invalid_process_payload("missing_required"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        # 400 returns array of errors per spec
        if isinstance(body, list):
            assert_error_schema(body, ERROR_ARRAY_SCHEMA)

    def test_wrong_field_types_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/process",
            json=make_invalid_process_payload("wrong_type"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, base_url, gateway_headers_spec):
        response = requests.get(f"{base_url}/process")
        assert response.status_code == 401
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_bearer_token_returns_401(self, base_url, auth_headers, gateway_headers_spec):
        bad_headers = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = requests.get(f"{base_url}/process", headers=bad_headers)
        assert response.status_code == 401
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_uuid_path_param_returns_4xx(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/process/!!!bad-id!!!", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestStateNegativeContracts:
    def test_create_state_missing_required_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        import uuid
        fake_process_id = str(uuid.uuid4())
        response = requests.post(
            f"{base_url}/process/{fake_process_id}/state",
            json={},
            headers=auth_headers,
        )
        # Either process not found (404) or validation error (400)
        assert response.status_code in (400, 404)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_state_invalid_uuid_returns_4xx(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/state/not-a-uuid", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestActionNegativeContracts:
    def test_create_action_missing_required_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        import uuid
        fake_state_id = str(uuid.uuid4())
        response = requests.post(
            f"{base_url}/state/{fake_state_id}/action",
            json={},
            headers=auth_headers,
        )
        assert response.status_code in (400, 404)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_action_invalid_uuid_returns_4xx(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/action/not-a-uuid", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)


class TestTransitionNegativeContracts:
    def test_missing_required_fields_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/transition",
            json=make_invalid_transition_payload("missing_required"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_entity_id_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/transition",
            json=make_invalid_transition_payload("missing_entity_id"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_process_id_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/transition",
            json=make_invalid_transition_payload("missing_process_id"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_unknown_process_id_returns_404(self, base_url, auth_headers, gateway_headers_spec):
        import uuid
        response = requests.post(
            f"{base_url}/transition",
            json={"processId": str(uuid.uuid4()), "entityId": "entity-001"},
            headers=auth_headers,
        )
        assert response.status_code in (404, 400)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


class TestEscalationNegativeContracts:
    def test_create_escalation_missing_required_returns_400(self, base_url, auth_headers, gateway_headers_spec):
        import uuid
        fake_process_id = str(uuid.uuid4())
        response = requests.post(
            f"{base_url}/process/{fake_process_id}/escalation",
            json={},
            headers=auth_headers,
        )
        assert response.status_code in (400, 404)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_escalation_invalid_uuid_returns_4xx(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/escalation/not-a-uuid", headers=auth_headers)
        assert response.status_code in (400, 404, 422)
        assert_gateway_headers(response, gateway_headers_spec)
