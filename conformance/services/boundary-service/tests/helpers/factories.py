import uuid


def make_boundary_code():
    return f"BOUND-{uuid.uuid4().hex[:8].upper()}"


def make_boundary(code=None, **overrides):
    """Single Boundary object. Required: code."""
    b = {"code": code or make_boundary_code()}
    b.update(overrides)
    return b


def make_boundary_request(codes=None, count=1, **overrides):
    """Valid BoundaryRequest body. Required: boundary (non-empty array)."""
    boundaries = [make_boundary(code=c) for c in (codes or [make_boundary_code() for _ in range(count)])]
    return {"boundary": boundaries, **overrides}


def make_hierarchy_definition(hierarchy_type=None, boundary_types=None):
    """
    BoundaryHierarchyDefinition payload for POST /hierarchy.
    boundary_types: list of dicts [{boundaryType, parentBoundaryType}]
    """
    ht = hierarchy_type or f"HIER-{uuid.uuid4().hex[:6].upper()}"
    bh = boundary_types or [
        {"boundaryType": "DISTRICT",  "parentBoundaryType": None,       "active": True},
        {"boundaryType": "BLOCK",     "parentBoundaryType": "DISTRICT",  "active": True},
        {"boundaryType": "VILLAGE",   "parentBoundaryType": "BLOCK",     "active": True},
    ]
    return {
        "boundaryHierarchy": {
            "hierarchyType": ht,
            "boundaryHierarchy": bh,
        }
    }


def make_boundary_relation(code, hierarchy_type, boundary_type, parent=None, **overrides):
    """Valid BoundaryRelationshipRequest body. Required: code, hierarchyType, boundaryType."""
    rel = {
        "boundaryRelationship": {
            "code": code,
            "hierarchyType": hierarchy_type,
            "boundaryType": boundary_type,
        }
    }
    if parent:
        rel["boundaryRelationship"]["parent"] = parent
    for k, v in overrides.items():
        rel["boundaryRelationship"][k] = v
    return rel


# ── Invalid payloads for negative tests ──────────────────────────────────────

def make_invalid_boundary_request(strategy="missing_required"):
    strategies = {
        "missing_required":      {},
        "empty_boundary_array":  {"boundary": []},
        "missing_code":          {"boundary": [{"geometry": None}]},
        "wrong_type":            {"boundary": "not-an-array"},
    }
    return strategies.get(strategy, {})


def make_invalid_hierarchy_request(strategy="missing_required"):
    strategies = {
        "missing_required": {},
        "empty_body":       {"boundaryHierarchy": {}},
    }
    return strategies.get(strategy, {})


def make_invalid_relation_request(strategy="missing_required"):
    strategies = {
        "missing_required":        {},
        "missing_hierarchy_type":  {"boundaryRelationship": {"code": "X", "boundaryType": "DISTRICT"}},
        "missing_boundary_type":   {"boundaryRelationship": {"code": "X", "hierarchyType": "ADMIN"}},
    }
    return strategies.get(strategy, {})
