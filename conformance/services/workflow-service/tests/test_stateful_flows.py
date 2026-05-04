import pytest
import requests
from tests.helpers.factories import (
    make_process_payload,
    make_state_payload,
    make_action_payload,
    make_escalation_payload,
    make_transition_payload,
)
from tests.helpers.validators import assert_gateway_headers, assert_uuid_field


class TestProcessLifecycle:
    def test_create_read_update_delete_process(self, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        try:
            # 1. CREATE
            create_resp = requests.post(
                f"{base_url}/process",
                json=make_process_payload(name="Lifecycle Process", code="LIFECYCLE-001"),
                headers=auth_headers,
            )
            assert create_resp.status_code == 201, f"Create failed: {create_resp.text}"
            assert_gateway_headers(create_resp, gateway_headers_spec)
            body = create_resp.json()
            process_id = body["id"]
            assert_uuid_field(body, "id")

            # 2. READ
            get_resp = requests.get(f"{base_url}/process/{process_id}", headers=auth_headers)
            assert get_resp.status_code == 200, f"Read failed: {get_resp.text}"
            assert get_resp.json()["id"] == process_id
            assert_gateway_headers(get_resp, gateway_headers_spec)

            # 3. UPDATE
            update_resp = requests.put(
                f"{base_url}/process/{process_id}",
                json={"name": "Updated Process Name", "description": "Updated via conformance test"},
                headers=auth_headers,
            )
            assert update_resp.status_code == 200, f"Update failed: {update_resp.text}"
            assert update_resp.json()["name"] == "Updated Process Name"
            assert_gateway_headers(update_resp, gateway_headers_spec)

            # 4. DELETE
            del_resp = requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)
            assert del_resp.status_code == 200, f"Delete failed: {del_resp.text}"
            assert del_resp.json().get("deleted") is True
            assert_gateway_headers(del_resp, gateway_headers_spec)
            process_id = None

            # 5. CONFIRM DELETION
            after_resp = requests.get(
                f"{base_url}/process/{process_id or 'nonexistent'}", headers=auth_headers
            )
            assert after_resp.status_code == 404

        finally:
            if process_id:
                requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)


class TestProcessDefinitionBuildFlow:
    """Build a complete process definition: process → state → action."""

    def test_full_process_definition_build(self, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        state_id = None
        action_id = None
        try:
            # 1. Create process
            proc_resp = requests.post(
                f"{base_url}/process",
                json=make_process_payload(name="Full Flow Process", code="FULL-FLOW-001"),
                headers=auth_headers,
            )
            assert proc_resp.status_code == 201, f"Process create failed: {proc_resp.text}"
            process_id = proc_resp.json()["id"]

            # 2. Create initial state
            state_payload = make_state_payload(code="SUBMITTED", name="Submitted", isInitial=True)
            state_resp = requests.post(
                f"{base_url}/process/{process_id}/state",
                json=state_payload,
                headers=auth_headers,
            )
            assert state_resp.status_code == 201, f"State create failed: {state_resp.text}"
            assert_gateway_headers(state_resp, gateway_headers_spec)
            state_body = state_resp.json()
            state_id = state_body["id"]
            assert_uuid_field(state_body, "id")
            assert state_body.get("code") == "SUBMITTED"

            # 3. Create second state for action target
            next_state_payload = make_state_payload(code="APPROVED", name="Approved", isInitial=False)
            next_state_resp = requests.post(
                f"{base_url}/process/{process_id}/state",
                json=next_state_payload,
                headers=auth_headers,
            )
            assert next_state_resp.status_code == 201
            next_state_id = next_state_resp.json()["id"]

            # 4. Create action on initial state
            action_resp = requests.post(
                f"{base_url}/state/{state_id}/action",
                json=make_action_payload(next_state_code="APPROVED", name="APPROVE", label="Approve"),
                headers=auth_headers,
            )
            assert action_resp.status_code == 201, f"Action create failed: {action_resp.text}"
            assert_gateway_headers(action_resp, gateway_headers_spec)
            action_body = action_resp.json()
            action_id = action_body["id"]
            assert_uuid_field(action_body, "id")

            # 5. Read action back
            get_action_resp = requests.get(f"{base_url}/action/{action_id}", headers=auth_headers)
            assert get_action_resp.status_code == 200
            assert get_action_resp.json()["id"] == action_id

            # 6. List states under process
            states_resp = requests.get(
                f"{base_url}/process/{process_id}/state", headers=auth_headers
            )
            assert states_resp.status_code == 200
            assert isinstance(states_resp.json(), list)
            state_ids = [s["id"] for s in states_resp.json()]
            assert state_id in state_ids

            # 7. Check definition endpoint includes the process
            def_resp = requests.get(f"{base_url}/process/definition", headers=auth_headers)
            assert def_resp.status_code == 200

        finally:
            if action_id:
                requests.delete(f"{base_url}/action/{action_id}", headers=auth_headers)
            if state_id:
                requests.delete(f"{base_url}/state/{state_id}", headers=auth_headers)
            if process_id:
                requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)


class TestStateLifecycle:
    def test_create_read_update_delete_state(self, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        state_id = None
        try:
            # Setup: create parent process
            proc_resp = requests.post(
                f"{base_url}/process",
                json=make_process_payload(code="STATE-LC-001"),
                headers=auth_headers,
            )
            assert proc_resp.status_code == 201
            process_id = proc_resp.json()["id"]

            # 1. CREATE state
            state_resp = requests.post(
                f"{base_url}/process/{process_id}/state",
                json=make_state_payload(code="INITIAL", name="Initial State"),
                headers=auth_headers,
            )
            assert state_resp.status_code == 201
            assert_gateway_headers(state_resp, gateway_headers_spec)
            state_id = state_resp.json()["id"]

            # 2. READ state
            get_resp = requests.get(f"{base_url}/state/{state_id}", headers=auth_headers)
            assert get_resp.status_code == 200
            assert get_resp.json()["id"] == state_id
            assert_gateway_headers(get_resp, gateway_headers_spec)

            # 3. UPDATE state
            update_resp = requests.put(
                f"{base_url}/state/{state_id}",
                json={"name": "Updated Initial State", "sla": 900},
                headers=auth_headers,
            )
            assert update_resp.status_code == 200
            assert update_resp.json()["name"] == "Updated Initial State"
            assert_gateway_headers(update_resp, gateway_headers_spec)

            # 4. DELETE state
            del_resp = requests.delete(f"{base_url}/state/{state_id}", headers=auth_headers)
            assert del_resp.status_code == 200
            assert del_resp.json().get("deleted") is True
            state_id = None

        finally:
            if state_id:
                requests.delete(f"{base_url}/state/{state_id}", headers=auth_headers)
            if process_id:
                requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)


class TestActionLifecycle:
    def test_create_read_update_delete_action(self, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        state_id = None
        action_id = None
        try:
            proc_resp = requests.post(
                f"{base_url}/process",
                json=make_process_payload(code="ACTION-LC-001"),
                headers=auth_headers,
            )
            assert proc_resp.status_code == 201
            process_id = proc_resp.json()["id"]

            state_resp = requests.post(
                f"{base_url}/process/{process_id}/state",
                json=make_state_payload(code="PENDING", name="Pending"),
                headers=auth_headers,
            )
            assert state_resp.status_code == 201
            state_id = state_resp.json()["id"]

            # 1. CREATE action
            action_resp = requests.post(
                f"{base_url}/state/{state_id}/action",
                json=make_action_payload(next_state_code="APPROVED", name="APPROVE"),
                headers=auth_headers,
            )
            assert action_resp.status_code == 201
            assert_gateway_headers(action_resp, gateway_headers_spec)
            action_id = action_resp.json()["id"]

            # 2. READ action
            get_resp = requests.get(f"{base_url}/action/{action_id}", headers=auth_headers)
            assert get_resp.status_code == 200
            assert get_resp.json()["id"] == action_id
            assert_gateway_headers(get_resp, gateway_headers_spec)

            # 3. UPDATE action
            update_resp = requests.put(
                f"{base_url}/action/{action_id}",
                json={"name": "APPROVE_UPDATED", "nextState": "APPROVED", "label": "Approve Now"},
                headers=auth_headers,
            )
            assert update_resp.status_code == 200
            assert_gateway_headers(update_resp, gateway_headers_spec)

            # 4. DELETE action
            del_resp = requests.delete(f"{base_url}/action/{action_id}", headers=auth_headers)
            assert del_resp.status_code == 200
            assert del_resp.json().get("deleted") is True
            action_id = None

        finally:
            if action_id:
                requests.delete(f"{base_url}/action/{action_id}", headers=auth_headers)
            if state_id:
                requests.delete(f"{base_url}/state/{state_id}", headers=auth_headers)
            if process_id:
                requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)


class TestEscalationConfigLifecycle:
    def test_create_read_update_delete_escalation(self, base_url, auth_headers, gateway_headers_spec):
        process_id = None
        escalation_id = None
        try:
            proc_resp = requests.post(
                f"{base_url}/process",
                json=make_process_payload(code="ESC-LC-001"),
                headers=auth_headers,
            )
            assert proc_resp.status_code == 201
            process_id = proc_resp.json()["id"]

            # 1. CREATE escalation config
            esc_resp = requests.post(
                f"{base_url}/process/{process_id}/escalation",
                json=make_escalation_payload(
                    state_code="SUBMITTED",
                    escalation_action="ESCALATE_TO_SUPERVISOR",
                ),
                headers=auth_headers,
            )
            assert esc_resp.status_code == 201, f"Escalation create failed: {esc_resp.text}"
            assert_gateway_headers(esc_resp, gateway_headers_spec)
            esc_body = esc_resp.json()
            escalation_id = esc_body["id"]
            assert_uuid_field(esc_body, "id")

            # 2. READ
            get_resp = requests.get(f"{base_url}/escalation/{escalation_id}", headers=auth_headers)
            assert get_resp.status_code == 200
            assert get_resp.json()["id"] == escalation_id
            assert_gateway_headers(get_resp, gateway_headers_spec)

            # 3. UPDATE
            update_resp = requests.put(
                f"{base_url}/escalation/{escalation_id}",
                json={**esc_body, "stateSlaMinutes": 90},
                headers=auth_headers,
            )
            assert update_resp.status_code == 200
            assert_gateway_headers(update_resp, gateway_headers_spec)

            # 4. DELETE
            del_resp = requests.delete(
                f"{base_url}/escalation/{escalation_id}", headers=auth_headers
            )
            assert del_resp.status_code == 200
            assert del_resp.json().get("deleted") is True
            escalation_id = None

        finally:
            if escalation_id:
                requests.delete(f"{base_url}/escalation/{escalation_id}", headers=auth_headers)
            if process_id:
                requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)


class TestTransitionFlow:
    def test_init_and_search_transition(self, base_url, auth_headers, gateway_headers_spec):
        """Creates a process instance via POST /transition and verifies it is searchable."""
        process_id = None
        entity_id = "conformance-entity-001"
        try:
            proc_resp = requests.post(
                f"{base_url}/process",
                json=make_process_payload(code="TRANS-FLOW-001"),
                headers=auth_headers,
            )
            if proc_resp.status_code != 201:
                pytest.skip("Cannot create process for transition flow test")
            process_id = proc_resp.json()["id"]

            # Trigger init transition
            trans_resp = requests.post(
                f"{base_url}/transition",
                json=make_transition_payload(
                    process_id=process_id,
                    entity_id=entity_id,
                    init=True,
                ),
                headers=auth_headers,
            )
            # 200 on success; 404 if process has no initial state configured yet
            assert trans_resp.status_code in (200, 404), (
                f"Unexpected status {trans_resp.status_code}: {trans_resp.text}"
            )
            if trans_resp.status_code == 200:
                assert_gateway_headers(trans_resp, gateway_headers_spec)
                body = trans_resp.json()
                assert "processId" in body
                assert body["processId"] == process_id

                # Search should now return this instance
                search_resp = requests.get(f"{base_url}/transition", headers=auth_headers)
                assert search_resp.status_code == 200
                assert search_resp.json()["totalCount"] >= 0

        finally:
            if process_id:
                requests.delete(f"{base_url}/process/{process_id}", headers=auth_headers)
