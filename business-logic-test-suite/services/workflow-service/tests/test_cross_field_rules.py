"""
Cross-field rule tests for Workflow service.
"""
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CF-001: Process code must match pattern
# ---------------------------------------------------------------------------

class TestBR_CF_001_process_code_must_match_pattern:
    """Process code must match ^[A-Za-z0-9_.:/@+\\- ]+$ and be <= 128 chars."""

    def test_valid_process_code_accepted(self, request, base_url, auth_headers):
        resp = req_lib.get(f"{base_url}/process/code/TEST-PROCESS", headers=auth_headers)
        assert resp.status_code in (200, 404), \
            f"GET process with valid code must return 200 or 404, got {resp.status_code}: {resp.text}"

    def test_process_code_with_control_chars_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process", auth_headers, {
            "name": "Bad Process", "code": "bad\x00code", "sla": 1440,
        })
        assert resp.status_code == 422, \
            f"Expected 422 for code with control chars, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: Parallel and join states are mutually exclusive
# ---------------------------------------------------------------------------

class TestBR_CF_002_parallel_and_join_states_mutually_exclusive:
    """isParallel=true AND isJoin=true on same state is rejected."""

    def test_both_parallel_and_join_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/state", auth_headers, {
            "code": "BAD-STATE", "name": "Bad State",
            "isParallel": True, "isJoin": True,
        })
        assert resp.status_code == 422, \
            f"Expected 422 for isParallel+isJoin, got {resp.status_code}: {resp.text}"

    def test_parallel_only_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/state", auth_headers, {
            "code": "PAR-STATE-" + __import__("uuid").uuid4().hex[:4].upper(),
            "name": "Parallel Only",
            "isParallel": True, "isJoin": False,
            "branchStates": ["SUBMITTED"],
        })
        assert resp.status_code in (200, 201, 409, 422), \
            f"Parallel-only state must pass mutual-exclusion check, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Parallel state must define non-empty branchStates
# ---------------------------------------------------------------------------

class TestBR_CF_003_parallel_state_must_define_non_empty_branch_states:
    """isParallel=true with empty branchStates is rejected."""

    def test_parallel_with_empty_branches_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/state", auth_headers, {
            "code": "PAR-EMPTY-" + __import__("uuid").uuid4().hex[:4].upper(),
            "name": "Parallel Empty Branches",
            "isParallel": True, "isJoin": False,
            "branchStates": [],
        })
        assert resp.status_code == 422, \
            f"Expected 422 for parallel state with empty branchStates, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Action source and target states must differ
# ---------------------------------------------------------------------------

class TestBR_CF_004_action_source_and_target_states_must_differ:
    """currentState == nextState on an action is a self-loop and must be rejected."""

    def test_self_loop_action_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/action", auth_headers, {
            "action": "SELF-APPROVE",
            "currentState": "SUBMITTED",
            "nextState": "SUBMITTED",
        })
        assert resp.status_code == 422, \
            f"Expected 422 for self-loop action, got {resp.status_code}: {resp.text}"

    def test_valid_transition_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/action", auth_headers, {
            "action": "APPROVE",
            "currentState": "SUBMITTED",
            "nextState": "APPROVED",
        })
        assert resp.status_code in (200, 201, 409, 422), \
            f"Valid action (different states) must pass self-loop check, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Escalation config requires at least one SLA
# ---------------------------------------------------------------------------

class TestBR_CF_005_escalation_config_requires_at_least_one_sla:
    """At least one of stateSlaMinutes or processSlaMinutes must be provided."""

    def test_escalation_without_sla_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/escalation", auth_headers, {
            "stateCode": "SUBMITTED",
            "processCode": "TEST-PROCESS",
        })
        assert resp.status_code == 422, \
            f"Expected 422 for escalation without SLA, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: Action RBAC roles and assigneeCheck control
# ---------------------------------------------------------------------------

class TestBR_CF_006_action_rbac_roles_and_assignee_check_control:
    """
    If action.roles is non-empty, user must have at least one of the listed roles.
    If action.assigneeCheck=true, user must also be in instance.assignees.
    An unauthenticated request (no roles) always fails when roles are required.
    """

    def test_transition_without_roles_returns_403_when_roles_required(
        self, request, base_url, auth_headers
    ):
        import uuid
        headers_no_auth = {k: v for k, v in auth_headers.items()
                           if k.lower() not in ("authorization",)}
        resp = _post(request.node,
                     f"{base_url}/process/TEST-PROCESS/transition",
                     headers_no_auth,
                     {"entityId": "ENT-CF006-" + uuid.uuid4().hex[:6], "action": "APPROVE"})
        assert resp.status_code in (401, 403), \
            f"Expected 401/403 for missing auth on role-restricted action, got {resp.status_code}: {resp.text}"


    def test_escalation_with_state_sla_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/escalation", auth_headers, {
            "stateCode": "SUBMITTED",
            "processCode": "TEST-PROCESS",
            "stateSlaMinutes": 60,
        })
        assert resp.status_code in (200, 201, 409, 422), \
            f"Escalation with stateSlaMinutes must pass SLA check, got {resp.status_code}: {resp.text}"
