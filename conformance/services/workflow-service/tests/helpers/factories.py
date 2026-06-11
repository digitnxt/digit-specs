import uuid


def make_process_payload(**overrides):
    base = {
        "name": "Test Process",
        "code": f"TEST-PROC-{uuid.uuid4().hex[:6].upper()}",
        "description": "Conformance test process",
        "version": "1.0",
        "sla": 3600,
    }
    return {**base, **overrides}


def make_state_payload(**overrides):
    base = {
        "code": f"STATE-{uuid.uuid4().hex[:6].upper()}",
        "name": "Test State",
        "description": "Conformance test state",
        "sla": 1800,
        "isInitial": True,
        "isParallel": False,
        "isJoin": False,
    }
    return {**base, **overrides}


def make_action_payload(next_state_code, **overrides):
    base = {
        "name": f"action-{uuid.uuid4().hex[:6]}",
        "label": "Test Action",
        "nextState": next_state_code,
    }
    return {**base, **overrides}


def make_escalation_payload(state_code, escalation_action, **overrides):
    base = {
        "stateCode": state_code,
        "escalationAction": escalation_action,
        "stateSlaMinutes": 60,
        "processSlaMinutes": 120,
    }
    return {**base, **overrides}


def make_transition_payload(process_code, entity_id, action="INITIATE", **overrides):
    base = {
        "processCode": process_code,
        "entityId": entity_id,
        "action": action,
    }
    return {**base, **overrides}


def make_invalid_process_payload(strategy="missing_required"):
    strategies = {
        "missing_required": {},
        "wrong_type": {"name": 12345, "code": False},
        "empty_name": {"name": "", "code": "VALID-CODE"},
    }
    return strategies.get(strategy, {})


def make_invalid_transition_payload(strategy="missing_required"):
    strategies = {
        "missing_required": {},
        "missing_entity_id": {"processCode": "PROC-001", "action": "INITIATE"},
        "missing_process_code": {"entityId": "entity-001", "action": "INITIATE"},
        "missing_action": {"processCode": "PROC-001", "entityId": "entity-001"},
    }
    return strategies.get(strategy, {})
