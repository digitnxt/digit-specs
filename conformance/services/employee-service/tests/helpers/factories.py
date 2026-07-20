import uuid


def _uid():
    return uuid.uuid4().hex[:8].upper()


# ── Employee ──────────────────────────────────────────────────────────────────

def make_employee(**overrides):
    """
    Minimal valid employee create payload. Required by the contract:
    employeeType, department, designation.

    userId / individualId are deliberately omitted: when Keycloak / the
    Individual service are enabled the service round-trips those references and
    rejects unknown ids (see validateUserID / validateIndividualID), which would
    make creates fail for reasons unrelated to the employee contract. Omitting
    them keeps the happy path independent of those downstream toggles.
    """
    base = {
        "employeeType": "PERMANENT",
        "department": f"DEPT-{_uid()}",
        "designation": f"DESIG-{_uid()}",
        "isActive": True,
    }
    return {**base, **overrides}


def make_employee_update(version, **overrides):
    """
    PUT /employees/{id} is a strict full-state declaration: employeeType,
    department, designation, status, isActive, jurisdictions AND the
    optimistic-concurrency version are all required (see UpdateEmployeeRequest).
    """
    base = {
        "employeeType": "CONTRACT",
        "department": f"DEPT-{_uid()}",
        "designation": f"DESIG-{_uid()}",
        "status": "ACTIVE",
        "isActive": True,
        "jurisdictions": [],
        "version": version,
    }
    return {**base, **overrides}


def make_employee_patch(version, **overrides):
    """
    PATCH /employees/{id} requires version plus at least one mutable field.
    """
    base = {"version": version, "department": f"DEPT-{_uid()}"}
    return {**base, **overrides}


# ── Boundary / Jurisdiction ──────────────────────────────────────────────────

def make_boundary_relation(code=None, boundary_type="Ward", hierarchy_type="ADMIN"):
    """
    A single boundaryRelation entry: {code, boundaryType, hierarchyType}.
    All three fields are required (see the Boundary schema / models.BoundaryRef).
    """
    return {
        "code": code or f"WARD-{_uid()}",
        "boundaryType": boundary_type,
        "hierarchyType": hierarchy_type,
    }


def make_jurisdiction_create(boundary_relations=None, **overrides):
    """
    POST /employees/{id}/jurisdictions body. The owning employeeId comes from
    the URL path and is NOT part of the body (see CreateJurisdictionRequest).
    """
    base = {
        "boundaryRelation": boundary_relations or [make_boundary_relation()],
        "isActive": True,
    }
    return {**base, **overrides}


def make_jurisdiction_update(version, boundary_relations=None, **overrides):
    """
    PUT /employees/{id}/jurisdictions/{jid} body: boundaryRelation (required,
    fully replaces) + version (required). employeeId is immutable / path-scoped.
    """
    base = {
        "boundaryRelation": boundary_relations or [make_boundary_relation()],
        "version": version,
    }
    return {**base, **overrides}


# ── Negative payloads ────────────────────────────────────────────────────────

def make_invalid_employee(strategy="missing_required"):
    strategies = {
        "missing_required":        {},
        "missing_employee_type":   {"department": "DEPT-X", "designation": "DESIG-X"},
        "missing_department":      {"employeeType": "PERMANENT", "designation": "DESIG-X"},
        "missing_designation":     {"employeeType": "PERMANENT", "department": "DEPT-X"},
        "wrong_type":              {"employeeType": 12345, "department": True, "designation": None},
    }
    return strategies.get(strategy, {})


def make_invalid_jurisdiction(strategy="missing_required"):
    """
    Invalid POST /employees/{id}/jurisdictions bodies. Note employeeId is never
    part of the body — the only structural requirement is a non-empty
    boundaryRelation array of well-formed entries.
    """
    strategies = {
        "missing_required":          {},
        "missing_boundary_relation": {"isActive": True},
        "empty_boundary_relation":   {"boundaryRelation": []},
        "boundary_relation_as_strings": {"boundaryRelation": ["WARD-001", "WARD-002"]},
        "incomplete_boundary_entry": {"boundaryRelation": [{"code": "WARD-001"}]},
    }
    return strategies.get(strategy, {})
