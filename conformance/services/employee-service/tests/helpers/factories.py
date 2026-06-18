import uuid


def _uid():
    return uuid.uuid4().hex[:8].upper()


def make_employee(**overrides):
    base = {
        "employeeType": "PERMANENT",
        "department": f"DEPT-{_uid()}",
        "designation": f"DESIG-{_uid()}",
        "isActive": True,
    }
    return {**base, **overrides}


def make_employee_update(**overrides):
    base = {
        "employeeType": "CONTRACT",
        "isActive": True,
    }
    return {**base, **overrides}


def make_employee_patch(**overrides):
    base = {"isActive": True}
    return {**base, **overrides}


def make_jurisdiction(employee_id, boundary_relations=None, **overrides):
    base = {
        "employeeId": str(employee_id),
        "boundaryRelation": boundary_relations or [f"BOUNDARY-{_uid()}"],
        "isActive": True,
    }
    return {**base, **overrides}


def make_invalid_employee(strategy="missing_required"):
    strategies = {
        "missing_required":        {},
        "missing_employee_type":   {"department": "DEPT-X", "designation": "DESIG-X"},
        "missing_department":      {"employeeType": "PERMANENT", "designation": "DESIG-X"},
        "missing_designation":     {"employeeType": "PERMANENT", "department": "DEPT-X"},
        "empty_array":             [],
        "wrong_type":              {"employeeType": 12345, "department": True, "designation": None},
    }
    return strategies.get(strategy, {})


def make_invalid_jurisdiction(strategy="missing_required"):
    strategies = {
        "missing_required":          {},
        "missing_employee_id":       {"boundaryRelation": ["BOUNDARY-X"]},
        "missing_boundary_relation": {"employeeId": str(uuid.uuid4())},
        "empty_boundary_relation":   {"employeeId": str(uuid.uuid4()), "boundaryRelation": []},
    }
    return strategies.get(strategy, {})
