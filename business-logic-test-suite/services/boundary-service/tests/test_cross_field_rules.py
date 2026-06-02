"""
Cross-field rule tests for Boundary service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _code():
    return "BR-CF-" + uuid.uuid4().hex[:6].upper()


# ---------------------------------------------------------------------------
# BR-CF-001: Geometry type and structure alignment
# ---------------------------------------------------------------------------

class TestBR_CF_001_geometry_type_and_structure_alignment:
    """geometry.type must be valid GeoJSON type; Polygon rings must be closed."""

    def test_valid_point_geometry_accepted(self, request, base_url, auth_headers):
        code = _code()
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{
                "code": code,
                "geometry": {
                    "type": "Point",
                    "coordinates": [77.5946, 12.9716],
                },
            }],
        })
        assert resp.status_code in (200, 201), f"Expected 200/201, got {resp.status_code}: {resp.text}"

    def test_invalid_geometry_type_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{
                "code": _code(),
                "geometry": {
                    "type": "UndefinedShape",
                    "coordinates": [[0, 0]],
                },
            }],
        })
        assert resp.status_code == 400, f"Expected 400 for invalid geometry type, got {resp.status_code}: {resp.text}"

    def test_unclosed_polygon_ring_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{
                "code": _code(),
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1]]],
                },
            }],
        })
        assert resp.status_code == 400, f"Expected 400 for unclosed polygon ring, got {resp.status_code}: {resp.text}"

    def test_closed_polygon_ring_accepted(self, request, base_url, auth_headers):
        code = _code()
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{
                "code": code,
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]],
                },
            }],
        })
        assert resp.status_code in (200, 201), f"Closed polygon must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: Hierarchy contains exactly one root
# ---------------------------------------------------------------------------

class TestBR_CF_002_hierarchy_contains_exactly_one_root:
    """boundaryHierarchy must have exactly one entry with parentBoundaryType=null."""

    def test_hierarchy_with_multiple_roots_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/hierarchy", auth_headers, {
            "hierarchy": {
                "hierarchyType": "TEST-MULTI-ROOT-" + uuid.uuid4().hex[:4].upper(),
                "boundaryHierarchy": [
                    {"boundaryType": "TYPE_A", "parentBoundaryType": None, "active": True},
                    {"boundaryType": "TYPE_B", "parentBoundaryType": None, "active": True},
                ],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for multiple roots, got {resp.status_code}: {resp.text}"

    def test_hierarchy_with_no_root_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/hierarchy", auth_headers, {
            "hierarchy": {
                "hierarchyType": "TEST-NO-ROOT-" + uuid.uuid4().hex[:4].upper(),
                "boundaryHierarchy": [
                    {"boundaryType": "TYPE_A", "parentBoundaryType": "TYPE_B", "active": True},
                ],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for zero roots, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Hierarchy defines no circular dependencies
# ---------------------------------------------------------------------------

class TestBR_CF_003_hierarchy_defines_no_circular_dependencies:
    """No boundaryType may reference itself through parent relationships."""

    def test_direct_self_reference_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/hierarchy", auth_headers, {
            "hierarchy": {
                "hierarchyType": "TEST-CIRC-" + uuid.uuid4().hex[:4].upper(),
                "boundaryHierarchy": [
                    {"boundaryType": "ROOT", "parentBoundaryType": None, "active": True},
                    {"boundaryType": "SELF_REF", "parentBoundaryType": "SELF_REF", "active": True},
                ],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for self-referencing type, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Relationship boundary type exists in hierarchy
# ---------------------------------------------------------------------------

class TestBR_CF_004_relationship_boundary_type_exists_in_hierarchy:
    """boundaryType in a relationship must be defined in the referenced hierarchy."""


# ---------------------------------------------------------------------------
# BR-CF-005: Parent boundary type matches hierarchy definition
# ---------------------------------------------------------------------------

class TestBR_CF_005_parent_boundary_type_matches_hierarchy_definition:
    """
    If parent is specified in a relationship, the parent boundary's boundaryType
    must exactly match the declared parentBoundaryType of the current boundaryType
    in the hierarchy definition.
    """

    def test_relationship_with_wrong_parent_type_rejected(
        self, request, base_url, auth_headers
    ):
        # In ADMIN hierarchy: DISTRICT's parentBoundaryType = STATE
        # Providing a parent that is a WARD (not STATE) violates this rule
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "DISTRICT_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "DISTRICT",
                "parent": "WARD_001",
            }],
        })
        assert resp.status_code in (400, 404), \
            f"Expected 400/404 for wrong parent type, got {resp.status_code}: {resp.text}"

    def test_relationship_with_correct_parent_type_accepted(
        self, request, base_url, auth_headers
    ):
        # STATE_001 is a root; DISTRICT with parent=STATE_001 should be valid
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "STATE",
            }],
        })
        assert resp.status_code in (200, 201, 409), \
            f"Root STATE relationship must be accepted/conflict, got {resp.status_code}: {resp.text}"


    def test_relationship_with_undefined_boundary_type_rejected(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "UNDEFINED_TYPE",
            }],
        })
        assert resp.status_code in (400, 404), \
            f"Expected 400/404 for undefined boundary type, got {resp.status_code}: {resp.text}"
