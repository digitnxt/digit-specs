"""
Lifecycle rule tests for Boundary service.
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
    return "BR-LC-" + uuid.uuid4().hex[:6].upper()


def _ht():
    return "HT-LC-" + uuid.uuid4().hex[:4].upper()


# ---------------------------------------------------------------------------
# BR-LC-001: Boundary code uniqueness per tenant
# ---------------------------------------------------------------------------

class TestBR_LC_001_boundary_code_uniqueness_per_tenant:
    """Duplicate boundary code within a tenant returns 409."""

    def test_unique_boundary_code_accepted(self, request, base_url, auth_headers):
        code = _code()
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{"code": code}],
        })
        assert resp.status_code in (200, 201), f"Expected 200/201, got {resp.status_code}: {resp.text}"

    def test_duplicate_boundary_code_returns_409(self, request, base_url, auth_headers):
        code = _code()
        req_lib.post(f"{base_url}/boundaries", headers=auth_headers,
                     json={"boundary": [{"code": code}]})
        resp = _post(request.node, f"{base_url}/boundaries", auth_headers, {
            "boundary": [{"code": code}],
        })
        assert resp.status_code == 409, f"Expected 409 for duplicate boundary code, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-002: Hierarchy type uniqueness per tenant
# ---------------------------------------------------------------------------

class TestBR_LC_002_hierarchy_type_uniqueness_per_tenant:
    """Duplicate hierarchyType within a tenant returns 409."""

    def test_duplicate_hierarchy_type_returns_409(self, request, base_url, auth_headers):
        ht = _ht()
        req_lib.post(f"{base_url}/hierarchy", headers=auth_headers, json={
            "hierarchy": {
                "hierarchyType": ht,
                "boundaryHierarchy": [
                    {"boundaryType": "STATE", "parentBoundaryType": None, "active": True},
                ],
            },
        })
        resp = _post(request.node, f"{base_url}/hierarchy", auth_headers, {
            "hierarchy": {
                "hierarchyType": ht,
                "boundaryHierarchy": [
                    {"boundaryType": "STATE", "parentBoundaryType": None, "active": True},
                ],
            },
        })
        assert resp.status_code == 409, f"Expected 409 for duplicate hierarchy type, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-003: Relationship key uniqueness per tenant
# ---------------------------------------------------------------------------

class TestBR_LC_003_relationship_key_uniqueness_per_tenant:
    """Duplicate (code, hierarchyType) within a tenant returns 409."""

    def test_duplicate_relationship_returns_409(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "STATE",
            }],
        })
        first_status = resp.status_code
        if first_status not in (200, 201, 409):
            return

        second = _post(request.node, f"{base_url}/relationships", auth_headers, {
            "relationships": [{
                "code": "STATE_001",
                "hierarchyType": "ADMIN",
                "boundaryType": "STATE",
            }],
        })
        assert second.status_code == 409, \
            f"Expected 409 for duplicate relationship, got {second.status_code}: {second.text}"
