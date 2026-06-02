"""
Cross-schema rule tests for Boundary service.
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
# BR-CS-001: Relationship references existing boundary entity
# ---------------------------------------------------------------------------

class TestBR_CS_001_relationship_references_existing_boundary_entity:
    """Creating a relationship for a non-existent boundary code returns 404."""

    def test_relationship_for_nonexistent_code_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "NONEXISTENT-CODE-" + uuid.uuid4().hex[:6].upper(),
                "hierarchyType": "ADMIN",
                "boundaryType": "STATE",
            }],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent boundary code, got {resp.status_code}: {resp.text}"

    def test_relationship_for_existing_code_accepted(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "STATE",
            }],
        })
        assert resp.status_code in (200, 201, 409), \
            f"Expected 200/201/409 for existing boundary code, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-002: Relationship references existing hierarchy definition
# ---------------------------------------------------------------------------

class TestBR_CS_002_relationship_references_existing_hierarchy:
    """Creating a relationship for a non-existent hierarchyType returns 404."""

    def test_relationship_for_nonexistent_hierarchy_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "NONEXISTENT-HIERARCHY-" + uuid.uuid4().hex[:4].upper(),
                "boundaryType": "STATE",
            }],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent hierarchyType, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-003: Parent relationship record must exist
# ---------------------------------------------------------------------------

class TestBR_CS_003_parent_relationship_record_must_exist:
    """If parent is specified, a relationship for that parent code must already exist."""

    def test_child_relationship_with_nonexistent_parent_returns_404(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "DISTRICT",
                "parent": "NONEXISTENT-PARENT-" + uuid.uuid4().hex[:4].upper(),
            }],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent parent, got {resp.status_code}: {resp.text}"
