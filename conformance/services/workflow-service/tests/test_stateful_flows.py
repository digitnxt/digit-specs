import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_process_payload,
    make_state_payload,
    make_action_payload,
    make_escalation_payload,
    make_transition_payload,
)
from tests.helpers.validators import assert_gateway_headers, assert_uuid_field


def _send(node, method, url, headers=None, json_body=None):
    """Prepare, attach cURL (for HTML report), then send."""
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(url, headers):
    try:
        req_lib.delete(url, headers=headers)
    except Exception:
        pass


class TestProcessLifecycle:
    def test_create_read_update_delete_process(self, request, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        try:
            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/process",
                      headers=auth_headers,
                      json_body=make_process_payload(name="Lifecycle Process", code="LIFECYCLE-001"))
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            process_id = r.json()["id"]
            assert_uuid_field(r.json(), "id")

            # 2. READ
            r = _send(request.node, "GET",
                      f"{base_url}/process/{process_id}", headers=auth_headers)
            assert r.status_code == 200, f"Read failed: {r.text}"
            assert r.json()["id"] == process_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. UPDATE
            r = _send(request.node, "PUT", f"{base_url}/process/{process_id}",
                      headers=auth_headers,
                      json_body={"name": "Updated Process Name", "description": "Updated via conformance test"})
            assert r.status_code == 200, f"Update failed: {r.text}"
            assert r.json()["name"] == "Updated Process Name"
            assert_gateway_headers(r, gateway_headers_spec)

            # 4. DELETE
            r = _send(request.node, "DELETE",
                      f"{base_url}/process/{process_id}", headers=auth_headers)
            assert r.status_code == 200, f"Delete failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)
            deleted_id = process_id
            process_id = None

            # 5. CONFIRM DELETION
            r = _send(request.node, "GET",
                      f"{base_url}/process/{deleted_id}", headers=auth_headers)
            assert r.status_code == 404

        finally:
            if process_id:
                _cleanup(f"{base_url}/process/{process_id}", auth_headers)


class TestProcessDefinitionBuildFlow:
    """Build a complete process definition: process → state → action."""

    def test_full_process_definition_build(self, request, base_url, auth_headers, gateway_headers_spec):
        process_id = state_id = action_id = next_state_id = None
        try:
            # 1. Create process
            r = _send(request.node, "POST", f"{base_url}/process",
                      headers=auth_headers,
                      json_body=make_process_payload(name="Full Flow Process", code="FULL-FLOW-001"))
            assert r.status_code == 201, f"Process create failed: {r.text}"
            process_id = r.json()["id"]

            # 2. Create initial state
            r = _send(request.node, "POST", f"{base_url}/process/{process_id}/state",
                      headers=auth_headers,
                      json_body=make_state_payload(code="SUBMITTED", name="Submitted", isInitial=True))
            assert r.status_code == 201, f"State create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            state_id = r.json()["id"]
            assert_uuid_field(r.json(), "id")
            assert r.json().get("code") == "SUBMITTED"

            # 3. Create second state
            r = _send(request.node, "POST", f"{base_url}/process/{process_id}/state",
                      headers=auth_headers,
                      json_body=make_state_payload(code="APPROVED", name="Approved", isInitial=False))
            assert r.status_code == 201
            next_state_id = r.json()["id"]

            # 4. Create action on initial state
            r = _send(request.node, "POST", f"{base_url}/state/{state_id}/action",
                      headers=auth_headers,
                      json_body=make_action_payload(next_state_code="APPROVED", name="APPROVE", label="Approve"))
            assert r.status_code == 201, f"Action create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            action_id = r.json()["id"]
            assert_uuid_field(r.json(), "id")

            # 5. Read action back
            r = _send(request.node, "GET", f"{base_url}/action/{action_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == action_id

            # 6. List states under process
            r = _send(request.node, "GET", f"{base_url}/process/{process_id}/state", headers=auth_headers)
            assert r.status_code == 200
            assert isinstance(r.json(), list)
            assert state_id in [s["id"] for s in r.json()]

            # 7. Check definition endpoint
            r = _send(request.node, "GET", f"{base_url}/process/definition", headers=auth_headers)
            assert r.status_code == 200

        finally:
            if action_id:
                _cleanup(f"{base_url}/action/{action_id}", auth_headers)
            if state_id:
                _cleanup(f"{base_url}/state/{state_id}", auth_headers)
            if next_state_id:
                _cleanup(f"{base_url}/state/{next_state_id}", auth_headers)
            if process_id:
                _cleanup(f"{base_url}/process/{process_id}", auth_headers)


class TestStateLifecycle:
    def test_create_read_update_delete_state(self, request, base_url, auth_headers, gateway_headers_spec):
        process_id = state_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/process",
                      headers=auth_headers, json_body=make_process_payload(code="STATE-LC-001"))
            assert r.status_code == 201
            process_id = r.json()["id"]

            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/process/{process_id}/state",
                      headers=auth_headers,
                      json_body=make_state_payload(code="INITIAL", name="Initial State"))
            assert r.status_code == 201
            assert_gateway_headers(r, gateway_headers_spec)
            state_id = r.json()["id"]

            # 2. READ
            r = _send(request.node, "GET", f"{base_url}/state/{state_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == state_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. UPDATE
            r = _send(request.node, "PUT", f"{base_url}/state/{state_id}",
                      headers=auth_headers,
                      json_body={"name": "Updated Initial State", "sla": 900})
            assert r.status_code == 200
            assert r.json()["name"] == "Updated Initial State"
            assert_gateway_headers(r, gateway_headers_spec)

            # 4. DELETE
            r = _send(request.node, "DELETE", f"{base_url}/state/{state_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json().get("deleted") is True
            state_id = None

        finally:
            if state_id:
                _cleanup(f"{base_url}/state/{state_id}", auth_headers)
            if process_id:
                _cleanup(f"{base_url}/process/{process_id}", auth_headers)


class TestActionLifecycle:
    def test_create_read_update_delete_action(self, request, base_url, auth_headers, gateway_headers_spec):
        process_id = state_id = action_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/process",
                      headers=auth_headers, json_body=make_process_payload(code="ACTION-LC-001"))
            assert r.status_code == 201
            process_id = r.json()["id"]

            r = _send(request.node, "POST", f"{base_url}/process/{process_id}/state",
                      headers=auth_headers,
                      json_body=make_state_payload(code="PENDING", name="Pending"))
            assert r.status_code == 201
            state_id = r.json()["id"]

            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/state/{state_id}/action",
                      headers=auth_headers,
                      json_body=make_action_payload(next_state_code="APPROVED", name="APPROVE"))
            assert r.status_code == 201
            assert_gateway_headers(r, gateway_headers_spec)
            action_id = r.json()["id"]

            # 2. READ
            r = _send(request.node, "GET", f"{base_url}/action/{action_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == action_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. UPDATE
            r = _send(request.node, "PUT", f"{base_url}/action/{action_id}",
                      headers=auth_headers,
                      json_body={"name": "APPROVE_UPDATED", "nextState": "APPROVED", "label": "Approve Now"})
            assert r.status_code == 200
            assert_gateway_headers(r, gateway_headers_spec)

            # 4. DELETE
            r = _send(request.node, "DELETE", f"{base_url}/action/{action_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json().get("deleted") is True
            action_id = None

        finally:
            if action_id:
                _cleanup(f"{base_url}/action/{action_id}", auth_headers)
            if state_id:
                _cleanup(f"{base_url}/state/{state_id}", auth_headers)
            if process_id:
                _cleanup(f"{base_url}/process/{process_id}", auth_headers)


class TestEscalationConfigLifecycle:
    def test_create_read_update_delete_escalation(self, request, base_url, auth_headers, gateway_headers_spec):
        process_id = escalation_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/process",
                      headers=auth_headers, json_body=make_process_payload(code="ESC-LC-001"))
            assert r.status_code == 201
            process_id = r.json()["id"]

            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/process/{process_id}/escalation",
                      headers=auth_headers,
                      json_body=make_escalation_payload(state_code="SUBMITTED",
                                                        escalation_action="ESCALATE_TO_SUPERVISOR"))
            assert r.status_code == 201, f"Escalation create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            esc_body = r.json()
            escalation_id = esc_body["id"]
            assert_uuid_field(esc_body, "id")

            # 2. READ
            r = _send(request.node, "GET", f"{base_url}/escalation/{escalation_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == escalation_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. UPDATE
            r = _send(request.node, "PUT", f"{base_url}/escalation/{escalation_id}",
                      headers=auth_headers, json_body={**esc_body, "stateSlaMinutes": 90})
            assert r.status_code == 200
            assert_gateway_headers(r, gateway_headers_spec)

            # 4. DELETE
            r = _send(request.node, "DELETE",
                      f"{base_url}/escalation/{escalation_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json().get("deleted") is True
            escalation_id = None

        finally:
            if escalation_id:
                _cleanup(f"{base_url}/escalation/{escalation_id}", auth_headers)
            if process_id:
                _cleanup(f"{base_url}/process/{process_id}", auth_headers)


class TestTransitionFlow:
    def test_init_and_search_transition(self, request, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        entity_id = "conformance-entity-001"
        try:
            r = _send(request.node, "POST", f"{base_url}/process",
                      headers=auth_headers, json_body=make_process_payload(code="TRANS-FLOW-001"))
            if r.status_code != 201:
                pytest.skip("Cannot create process for transition flow test")
            process_id = r.json()["id"]

            r = _send(request.node, "POST", f"{base_url}/transition",
                      headers=auth_headers,
                      json_body=make_transition_payload(process_id=process_id,
                                                        entity_id=entity_id, init=True))
            assert r.status_code in (200, 404), f"Unexpected status {r.status_code}: {r.text}"
            if r.status_code == 200:
                assert_gateway_headers(r, gateway_headers_spec)
                body = r.json()
                assert "processId" in body
                assert body["processId"] == process_id

                r = _send(request.node, "GET", f"{base_url}/transition", headers=auth_headers)
                assert r.status_code == 200
                assert r.json()["totalCount"] >= 0

        finally:
            if process_id:
                _cleanup(f"{base_url}/process/{process_id}", auth_headers)
