"""
Cross-schema rule tests for Workflow service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CS-001: State requires existing process
# ---------------------------------------------------------------------------

class TestBR_CS_001_state_requires_existing_process:
    """States cannot be created for non-existent processes."""

    def test_state_for_nonexistent_process_returns_404(self, request, base_url, auth_headers):
        resp = _post(request.node,
                     f"{base_url}/process/NONEXISTENT-PROCESS-{uuid.uuid4().hex[:4]}/state",
                     auth_headers, {
                         "code": "STATE-001", "name": "State One", "isInitial": True, "sla": 60,
                     })
        assert resp.status_code == 404, \
            f"Expected 404 for state on nonexistent process, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: Instance requires existing process
# ---------------------------------------------------------------------------

class TestBR_CS_004_instance_requires_existing_process:
    """ProcessInstance cannot be created for a non-existent process."""

    def test_transition_for_nonexistent_process_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/process/transition", auth_headers, {
            "processId": f"NONEXISTENT-PROC-{uuid.uuid4().hex[:4]}",
            "action": "APPROVE",
            "entityId": "ENT-001",
        })
        assert resp.status_code == 404, \
            f"Expected 404 for transition on nonexistent process, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-005: Transition action must be valid for current state
# ---------------------------------------------------------------------------

class TestBR_CS_005_transition_action_must_be_valid_for_current_state:
    """Performing an action not defined for the current state is rejected."""

    def test_undefined_action_for_state_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/transition", auth_headers, {
            "entityId": "ENT-" + uuid.uuid4().hex[:6],
            "action": "NONEXISTENT-ACTION-" + uuid.uuid4().hex[:4],
        })
        assert resp.status_code in (404, 422), \
            f"Expected 404/422 for undefined action, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-006: Process code uniqueness per tenant
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-CS-002: Action states must belong to same process
# ---------------------------------------------------------------------------

class TestBR_CS_002_action_states_must_belong_to_same_process:
    """Both currentState and nextState in an action must be states of the same process."""

    def test_action_with_states_from_different_processes_rejected(
        self, request, base_url, auth_headers
    ):
        import uuid
        other_proc = "PROC-CS002-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/process", headers=auth_headers,
                     json={"name": "Other Process", "code": other_proc, "sla": 1440})
        req_lib.post(f"{base_url}/process/{other_proc}/state", headers=auth_headers,
                     json={"code": "OTHER-STATE", "name": "Other State", "sla": 60})

        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/action", auth_headers, {
            "action": "CROSS-PROC",
            "currentState": "SUBMITTED",
            "nextState": "OTHER-STATE",
        })
        assert resp.status_code in (404, 422), \
            f"Expected 404/422 for action crossing processes, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-003: Escalation config requires existing process
# ---------------------------------------------------------------------------

class TestBR_CS_003_escalation_config_requires_existing_process:
    """EscalationConfig must reference an existing process."""

    def test_escalation_for_nonexistent_process_returns_404(
        self, request, base_url, auth_headers
    ):
        import uuid
        resp = _post(request.node,
                     f"{base_url}/process/NONEXISTENT-PROC-{uuid.uuid4().hex[:4]}/escalation",
                     auth_headers, {
                         "stateCode": "SUBMITTED",
                         "processCode": "NONEXISTENT",
                         "stateSlaMinutes": 60,
                     })
        assert resp.status_code == 404, \
            f"Expected 404 for escalation on nonexistent process, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-008: Escalation config uniqueness per state per process
# ---------------------------------------------------------------------------

class TestBR_CS_008_escalation_config_uniqueness_per_state_per_process:
    """Only one escalation config per (tenant_id, process_id, state_code)."""

    def test_duplicate_escalation_config_returns_409(self, request, base_url, auth_headers):
        import uuid
        state_code = "SUBMITTED"
        body = {
            "stateCode": state_code,
            "processCode": "TEST-PROCESS",
            "stateSlaMinutes": 120,
        }
        req_lib.post(f"{base_url}/process/TEST-PROCESS/escalation",
                     headers=auth_headers, json=body)
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/escalation",
                     auth_headers, body)
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate escalation config, got {resp.status_code}: {resp.text}"


class TestBR_CS_006_process_code_uniqueness_per_tenant:
    """Duplicate process code returns 409."""

    def test_duplicate_process_code_returns_409(self, request, base_url, auth_headers):
        code = "PROC-DUP-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/process", headers=auth_headers,
                     json={"name": "Process One", "code": code, "sla": 1440})
        resp = _post(request.node, f"{base_url}/process", auth_headers,
                     {"name": "Process Two", "code": code, "sla": 1440})
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate process code, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-007: State code uniqueness within process
# ---------------------------------------------------------------------------

class TestBR_CS_007_state_code_uniqueness_within_process:
    """Duplicate state code within same process returns 409."""

    def test_duplicate_state_code_within_process_returns_409(
        self, request, base_url, auth_headers
    ):
        state_code = "STATE-DUP-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/process/TEST-PROCESS/state", headers=auth_headers,
                     json={"code": state_code, "name": "State One", "sla": 60})
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/state", auth_headers,
                     {"code": state_code, "name": "State Two", "sla": 60})
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate state code, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-009: Cannot delete process with existing instances
# ---------------------------------------------------------------------------

class TestBR_CS_009_cannot_delete_process_with_existing_instances:
    """Deleting TEST-PROCESS which has seed instances is rejected with 409."""

    def test_delete_process_with_instances_rejected(self, request, base_url, auth_headers):
        resp = req_lib.delete(f"{base_url}/process/TEST-PROCESS", headers=auth_headers)
        assert resp.status_code in (409, 422), \
            f"Expected 409/422 for delete with instances, got {resp.status_code}: {resp.text}"
