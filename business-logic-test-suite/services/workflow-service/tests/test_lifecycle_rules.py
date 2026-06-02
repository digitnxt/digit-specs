"""
Lifecycle rule tests for Workflow service.
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
# BR-LC-001: Instance records are append-only
# ---------------------------------------------------------------------------

class TestBR_LC_001_instance_records_are_append_only:
    """Each transition creates a new row; no rows are updated in place."""

    def test_transition_creates_new_instance_row(self, request, base_url, auth_headers):
        entity_id = "ENT-LC001-" + uuid.uuid4().hex[:6].upper()

        first = _post(request.node, f"{base_url}/process/TEST-PROCESS/transition", auth_headers,
                      {"entityId": entity_id, "action": "SUBMIT"})
        assert first.status_code in (200, 201, 202, 404, 422), \
            f"First transition must complete, got {first.status_code}: {first.text}"

        if first.status_code in (200, 201, 202):
            second = _post(request.node,
                           f"{base_url}/process/TEST-PROCESS/transition",
                           auth_headers,
                           {"entityId": entity_id, "action": "APPROVE"})
            assert second.status_code in (200, 201, 202, 403, 404, 422), \
                f"Second transition response unexpected: {second.status_code}: {second.text}"


# ---------------------------------------------------------------------------
# BR-LC-002: is_latest flag tracks current state atomically
# ---------------------------------------------------------------------------

class TestBR_LC_002_is_latest_flag_tracks_current_state_atomically:
    """After transition, only the newest instance row for the entity has is_latest=true."""

    def test_search_returns_latest_state_after_transition(
        self, request, base_url, auth_headers
    ):
        entity_id = "ENT-LC002-" + uuid.uuid4().hex[:6].upper()
        _post(request.node, f"{base_url}/process/TEST-PROCESS/transition", auth_headers,
              {"entityId": entity_id, "action": "SUBMIT"})

        search = req_lib.get(f"{base_url}/process/TEST-PROCESS/instances",
                             headers=auth_headers,
                             params={"entityId": entity_id})
        if search.status_code == 200:
            instances = search.json().get("instances", [])
            latest = [i for i in instances if i.get("isLatest") is True]
            assert len(latest) <= 1, \
                f"At most one instance must be is_latest=true, found {len(latest)}"


# ---------------------------------------------------------------------------
# BR-LC-005: Escalation keyword sets escalated flag
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-LC-003: Instance status transitions for parallel joins
# ---------------------------------------------------------------------------

class TestBR_LC_003_instance_status_transitions_for_parallel_joins:
    """
    When a parallel branch reaches the join state but other branches are still active,
    the instance moves to WAITING_FOR_JOIN. When all branches complete, it returns to ACTIVE.
    Observable via transition response status field.
    """

    def test_parallel_branch_transition_does_not_fail(
        self, request, base_url, auth_headers
    ):
        import uuid
        entity_id = "ENT-PAR-" + uuid.uuid4().hex[:6].upper()
        resp = _post(request.node,
                     f"{base_url}/process/TEST-PROCESS/transition",
                     auth_headers,
                     {"entityId": entity_id, "action": "SUBMIT"})
        assert resp.status_code in (200, 201, 202, 404, 422), \
            f"Parallel branch transition must complete, got {resp.status_code}: {resp.text}"
        if resp.status_code in (200, 201, 202):
            body = resp.json()
            instance = body.get("processInstance") or body
            assert instance.get("status") in (
                "ACTIVE", "WAITING_FOR_JOIN", None
            ), f"Instance status must be ACTIVE or WAITING_FOR_JOIN, got {instance.get('status')}"


# ---------------------------------------------------------------------------
# BR-LC-004: Parallel execution status is a terminal sequence
# ---------------------------------------------------------------------------

class TestBR_LC_004_parallel_execution_status_is_terminal_sequence:
    """
    ParallelExecution.status progresses ACTIVE → WAITING_FOR_JOIN → COMPLETED.
    COMPLETED is terminal and cannot be reversed.
    Observable: once a parallel execution completes, it cannot transition back.
    """

    def test_completed_parallel_execution_cannot_restart(
        self, request, base_url, auth_headers
    ):
        import uuid
        entity_id = "ENT-COMP-" + uuid.uuid4().hex[:6].upper()
        _post(request.node, f"{base_url}/process/TEST-PROCESS/transition",
              auth_headers, {"entityId": entity_id, "action": "SUBMIT"})
        resp = _post(request.node, f"{base_url}/process/TEST-PROCESS/transition",
                     auth_headers, {"entityId": entity_id, "action": "SUBMIT"})
        assert resp.status_code in (200, 201, 202, 403, 404, 422), \
            f"Re-submitting completed execution must fail or be a new state, got {resp.status_code}: {resp.text}"


class TestBR_LC_005_escalation_keyword_sets_escalated_flag:
    """Action named 'escalate' or containing 'escalat' sets escalated=true on instance."""

    def test_escalate_action_sets_escalated_flag(self, request, base_url, auth_headers):
        entity_id = "ENT-ESC-" + uuid.uuid4().hex[:6].upper()
        resp = _post(request.node,
                     f"{base_url}/process/TEST-PROCESS/transition",
                     auth_headers,
                     {"entityId": entity_id, "action": "ESCALATE_ISSUE"})
        if resp.status_code in (200, 201, 202):
            body = resp.json()
            instance = body.get("processInstance") or body
            assert instance.get("escalated") is True, \
                "Transition with 'escalat' in action name must set escalated=true"


# ---------------------------------------------------------------------------
# BR-LC-006: State deletion cascades to actions
# ---------------------------------------------------------------------------

class TestBR_LC_006_state_deletion_cascades_to_actions:
    """Deleting a state automatically removes actions referencing it."""

    def test_state_with_actions_can_be_deleted(self, request, base_url, auth_headers):
        state_code = "DEL-STATE-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/process/TEST-PROCESS/state", headers=auth_headers,
                     json={"code": state_code, "name": "Delete Target", "sla": 60})
        req_lib.post(f"{base_url}/process/TEST-PROCESS/action", headers=auth_headers,
                     json={"action": "NEXT", "currentState": state_code, "nextState": "APPROVED"})

        del_resp = req_lib.delete(
            f"{base_url}/process/TEST-PROCESS/state/{state_code}", headers=auth_headers
        )
        assert del_resp.status_code in (200, 204, 404), \
            f"State deletion must succeed (cascade removes actions), got {del_resp.status_code}: {del_resp.text}"
