import uuid
import requests as req_lib

from tests.helpers.curl_builder import attach_curl


def _send(node, method, url, headers=None, json_body=None, params=None):
    req = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = req.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _uniq(prefix):
    return f"{prefix}-{uuid.uuid4().hex[:8].upper()}"


def _cleanup_process(base_url, auth_headers, process_code):
    try:
        req_lib.delete(f"{base_url}/process/code/{process_code}", headers=auth_headers)
    except Exception:
        pass


class TestDefinitionAndCodeBasedLifecycle:
    def test_process_definition_crud(self, request, base_url, auth_headers):
        process_code = _uniq("DEF")
        definition = {
            "code": process_code,
            "name": "Definition Lifecycle",
            "description": "Conformance definition CRUD",
            "version": "1.0",
            "sla": 3600000,
            "states": [
                {
                    "code": "SUBMITTED",
                    "name": "Submitted",
                    "type": "INITIAL",
                    "sla": 600000,
                    "actions": [
                        {"code": "APPROVE", "label": "Approve", "nextState": "APPROVED"}
                    ],
                },
                {
                    "code": "APPROVED",
                    "name": "Approved",
                    "type": "NORMAL",
                    "sla": 600000,
                    "actions": [],
                },
            ],
        }

        try:
            r = _send(request.node, "POST", f"{base_url}/process/definition", headers=auth_headers, json_body=definition)
            assert r.status_code in (200, 201), f"create definition failed: {r.status_code} {r.text}"

            r = _send(request.node, "GET", f"{base_url}/process/definition/{process_code}", headers=auth_headers)
            assert r.status_code == 200, f"read definition failed: {r.status_code} {r.text}"

            update = {**definition, "name": "Definition Lifecycle Updated"}
            r = _send(request.node, "PUT", f"{base_url}/process/definition/{process_code}", headers=auth_headers, json_body=update)
            assert r.status_code == 200, f"update definition failed: {r.status_code} {r.text}"

            r = _send(request.node, "DELETE", f"{base_url}/process/definition/{process_code}", headers=auth_headers)
            assert r.status_code in (200, 204), f"delete definition failed: {r.status_code} {r.text}"
        finally:
            _cleanup_process(base_url, auth_headers, process_code)

    def test_code_based_state_action_escalation_and_transition(self, request, base_url, auth_headers):
        process_code = _uniq("WF")
        entity_id = f"entity-{uuid.uuid4().hex[:8]}"

        try:
            # Process lifecycle via code endpoints
            create_proc = {
                "name": "Workflow Lifecycle",
                "code": process_code,
                "description": "Conformance deterministic flow",
                "version": "1.0",
                "sla": 3600,
            }
            r = _send(request.node, "POST", f"{base_url}/process", headers=auth_headers, json_body=create_proc)
            assert r.status_code == 201, f"create process failed: {r.status_code} {r.text}"

            r = _send(request.node, "GET", f"{base_url}/process/code/{process_code}", headers=auth_headers)
            assert r.status_code == 200

            r = _send(
                request.node,
                "PUT",
                f"{base_url}/process/code/{process_code}",
                headers=auth_headers,
                json_body={"name": "Workflow Lifecycle Updated", "description": "Updated"},
            )
            assert r.status_code == 200

            # State lifecycle (code-based path)
            submitted_state = {
                "code": "SUBMITTED",
                "name": "Submitted",
                "description": "Initial state",
                "type": "INITIAL",
                "sla": 1800,
                "isInitial": True,
                "isParallel": False,
                "isJoin": False,
            }
            approved_state = {
                "code": "APPROVED",
                "name": "Approved",
                "description": "Approved state",
                "type": "NORMAL",
                "sla": 1800,
                "isInitial": False,
                "isParallel": False,
                "isJoin": False,
            }
            r = _send(request.node, "POST", f"{base_url}/process/{process_code}/state", headers=auth_headers, json_body=submitted_state)
            assert r.status_code == 201, f"create submitted state failed: {r.status_code} {r.text}"

            r = _send(request.node, "POST", f"{base_url}/process/{process_code}/state", headers=auth_headers, json_body=approved_state)
            assert r.status_code == 201, f"create approved state failed: {r.status_code} {r.text}"

            r = _send(request.node, "GET", f"{base_url}/process/{process_code}/state", headers=auth_headers)
            assert r.status_code == 200

            r = _send(request.node, "GET", f"{base_url}/process/{process_code}/state/SUBMITTED", headers=auth_headers)
            assert r.status_code == 200

            r = _send(
                request.node,
                "PUT",
                f"{base_url}/process/{process_code}/state/SUBMITTED",
                headers=auth_headers,
                json_body={"name": "Submitted Updated", "description": "Updated", "sla": 1700},
            )
            assert r.status_code == 200

            # Action lifecycle (code-based path)
            action_payload = {"code": "APPROVE", "label": "Approve", "nextState": "APPROVED"}
            r = _send(
                request.node,
                "POST",
                f"{base_url}/process/{process_code}/state/SUBMITTED/action",
                headers=auth_headers,
                json_body=action_payload,
            )
            assert r.status_code == 201, f"create action failed: {r.status_code} {r.text}"

            r = _send(request.node, "GET", f"{base_url}/process/{process_code}/state/SUBMITTED/action", headers=auth_headers)
            assert r.status_code == 200
            actions = r.json() if isinstance(r.json(), list) else []
            # Source of truth: pick the created action code from list response.
            action_code = None
            for a in actions:
                if a.get("code") == "APPROVE" or a.get("label") == "Approve":
                    action_code = a.get("code")
                    break
            if not action_code and actions:
                action_code = actions[0].get("code")
            assert action_code, f"Could not resolve actionCode from list response: {actions}"

            r = _send(request.node, "GET", f"{base_url}/process/{process_code}/state/SUBMITTED/action/{action_code}", headers=auth_headers)
            assert r.status_code == 200, f"read action failed for code={action_code}: {r.status_code} {r.text}"

            r = _send(
                request.node,
                "PUT",
                f"{base_url}/process/{process_code}/state/SUBMITTED/action/{action_code}",
                headers=auth_headers,
                json_body={"code": action_code, "label": "Approve Now", "nextState": "APPROVED"},
            )
            assert r.status_code == 200

            # Escalation lifecycle (code-based path)
            esc_payload = {"stateCode": "SUBMITTED", "escalationAction": "APPROVE", "stateSlaMinutes": 60, "processSlaMinutes": 120}
            r = _send(request.node, "POST", f"{base_url}/process/{process_code}/escalation", headers=auth_headers, json_body=esc_payload)
            assert r.status_code in (200, 201), f"create escalation failed: {r.status_code} {r.text}"

            r = _send(request.node, "GET", f"{base_url}/process/{process_code}/escalation", headers=auth_headers)
            assert r.status_code == 200

            r = _send(request.node, "GET", f"{base_url}/process/{process_code}/escalation/SUBMITTED", headers=auth_headers)
            assert r.status_code == 200

            r = _send(
                request.node,
                "PUT",
                f"{base_url}/process/{process_code}/escalation/SUBMITTED",
                headers=auth_headers,
                json_body={**esc_payload, "stateSlaMinutes": 90},
            )
            assert r.status_code == 200

            # Transition + search
            r = _send(
                request.node,
                "POST",
                f"{base_url}/transition",
                headers=auth_headers,
                json_body={"processCode": process_code, "entityId": entity_id, "action": "APPROVE"},
            )
            assert r.status_code == 200, f"transition failed: {r.status_code} {r.text}"

            r = _send(request.node, "GET", f"{base_url}/transition", headers=auth_headers)
            assert r.status_code == 200

            # auto escalate + search
            r = _send(request.node, "POST", f"{base_url}/auto/{process_code}/_escalate", headers=auth_headers)
            assert r.status_code in (200, 400, 404)

            r = _send(request.node, "GET", f"{base_url}/auto/_search", headers=auth_headers)
            assert r.status_code == 200

            # cleanup config resources in reverse order
            _send(request.node, "DELETE", f"{base_url}/process/{process_code}/escalation/SUBMITTED", headers=auth_headers)
            _send(request.node, "DELETE", f"{base_url}/process/{process_code}/state/SUBMITTED/action/{action_code}", headers=auth_headers)
            _send(request.node, "DELETE", f"{base_url}/process/{process_code}/state/APPROVED", headers=auth_headers)
            _send(request.node, "DELETE", f"{base_url}/process/{process_code}/state/SUBMITTED", headers=auth_headers)
        finally:
            _cleanup_process(base_url, auth_headers, process_code)
