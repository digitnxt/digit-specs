import pytest
import requests
from tests.helpers.factories import (
    make_boundary_code,
    make_boundary_request,
    make_hierarchy_definition,
    make_boundary_relation,
)
from tests.helpers.validators import assert_gateway_headers, assert_required_fields

# NOTE: The boundary service has no DELETE endpoints.
# Stateful tests create resources and verify read-back/update behaviour.
# Created resources persist in the service after the test run.


class TestBoundaryCreateAndSearch:
    def test_create_then_search_by_code(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()

        # 1. CREATE
        create_resp = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201, f"Create failed: {create_resp.text}"
        assert_gateway_headers(create_resp, gateway_headers_spec)
        created = create_resp.json()["boundary"]
        assert any(b["code"] == code for b in created), "Created code not in response"

        # 2. SEARCH by the known code
        search_resp = requests.get(
            f"{base_url}/boundaries",
            params={"codes": code},
            headers=auth_headers,
        )
        assert search_resp.status_code == 200, f"Search failed: {search_resp.text}"
        assert_gateway_headers(search_resp, gateway_headers_spec)
        found = search_resp.json()["boundary"]
        assert any(b["code"] == code for b in found), (
            f"Created boundary code '{code}' not returned in search"
        )

    def test_create_then_update_boundary(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()

        # 1. CREATE
        create_resp = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201, f"Create failed: {create_resp.text}"
        boundary_id = create_resp.json()["boundary"][0].get("id")
        if not boundary_id:
            pytest.skip("Service did not return boundary id — cannot test update")

        # 2. UPDATE
        update_resp = requests.put(
            f"{base_url}/boundaries/{boundary_id}",
            json={"code": code, "additionalAttributes": {"conformance": "updated"}},
            headers=auth_headers,
        )
        assert update_resp.status_code == 200, f"Update failed: {update_resp.text}"
        assert_gateway_headers(update_resp, gateway_headers_spec)
        assert_required_fields(update_resp.json(), ["boundary"])

    def test_create_batch_then_search_all(self, base_url, auth_headers, gateway_headers_spec):
        codes = [make_boundary_code() for _ in range(3)]

        # 1. CREATE batch
        create_resp = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=codes),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201

        # 2. SEARCH all three by codes (comma-separated via repeated param)
        search_resp = requests.get(
            f"{base_url}/boundaries",
            params=[("codes", c) for c in codes],
            headers=auth_headers,
        )
        assert search_resp.status_code == 200
        returned_codes = {b["code"] for b in search_resp.json().get("boundary", [])}
        for code in codes:
            assert code in returned_codes, f"Code '{code}' missing from batch search"


class TestHierarchyCreateAndSearch:
    def test_create_then_search_hierarchy(self, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"ADMIN-{make_boundary_code()}"

        # 1. CREATE
        create_resp = requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(hierarchy_type=hierarchy_type),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201, f"Create failed: {create_resp.text}"
        assert_gateway_headers(create_resp, gateway_headers_spec)
        assert_required_fields(create_resp.json(), ["hierarchy"])

        # 2. SEARCH
        search_resp = requests.get(
            f"{base_url}/hierarchy",
            params={"hierarchyType": hierarchy_type},
            headers=auth_headers,
        )
        assert search_resp.status_code == 200, f"Search failed: {search_resp.text}"
        assert_gateway_headers(search_resp, gateway_headers_spec)
        found = search_resp.json()["hierarchy"]
        assert any(h.get("hierarchyType") == hierarchy_type for h in found), (
            f"Hierarchy type '{hierarchy_type}' not found after creation"
        )

    def test_create_then_update_hierarchy(self, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"REVENUE-{make_boundary_code()}"

        # 1. CREATE
        create_resp = requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(hierarchy_type=hierarchy_type),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201
        hierarchy_id = None
        for h in create_resp.json().get("hierarchy", []):
            if h.get("hierarchyType") == hierarchy_type:
                hierarchy_id = h.get("id")
                break

        if not hierarchy_id:
            pytest.skip("Service did not return hierarchy id — cannot test update")

        # 2. UPDATE — add an extra level
        updated_payload = {
            "boundaryHierarchy": {
                "hierarchyType": hierarchy_type,
                "boundaryHierarchy": [
                    {"boundaryType": "STATE",    "parentBoundaryType": None,      "active": True},
                    {"boundaryType": "DISTRICT", "parentBoundaryType": "STATE",   "active": True},
                    {"boundaryType": "BLOCK",    "parentBoundaryType": "DISTRICT","active": True},
                ],
            }
        }
        update_resp = requests.put(
            f"{base_url}/hierarchy/{hierarchy_id}",
            json=updated_payload,
            headers=auth_headers,
        )
        assert update_resp.status_code == 200, f"Update failed: {update_resp.text}"
        assert_gateway_headers(update_resp, gateway_headers_spec)
        assert_required_fields(update_resp.json(), ["hierarchy"])


class TestRelationshipCreateAndSearch:
    def test_create_then_search_relationship(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"

        # 1. CREATE hierarchy (prerequisite)
        requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(hierarchy_type=hierarchy_type),
            headers=auth_headers,
        )

        # 2. CREATE relationship
        create_resp = requests.post(
            f"{base_url}/relationship",
            json=make_boundary_relation(
                code=code,
                hierarchy_type=hierarchy_type,
                boundary_type="DISTRICT",
            ),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201, f"Create failed: {create_resp.text}"
        assert_gateway_headers(create_resp, gateway_headers_spec)
        assert_required_fields(create_resp.json(), ["relationship"])

        # 3. SEARCH relationships
        search_resp = requests.get(
            f"{base_url}/relationship",
            params={"hierarchyType": hierarchy_type, "codes": code},
            headers=auth_headers,
        )
        assert search_resp.status_code == 200, f"Search failed: {search_resp.text}"
        assert_gateway_headers(search_resp, gateway_headers_spec)
        assert_required_fields(search_resp.json(), ["tenantBoundary"])

    def test_create_parent_child_relationship(self, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        parent_code = make_boundary_code()
        child_code  = make_boundary_code()

        # 1. CREATE hierarchy
        requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(
                hierarchy_type=hierarchy_type,
                boundary_types=[
                    {"boundaryType": "DISTRICT", "parentBoundaryType": None,       "active": True},
                    {"boundaryType": "BLOCK",    "parentBoundaryType": "DISTRICT",  "active": True},
                ],
            ),
            headers=auth_headers,
        )

        # 2. CREATE parent relationship (DISTRICT level — no parent)
        parent_resp = requests.post(
            f"{base_url}/relationship",
            json=make_boundary_relation(
                code=parent_code,
                hierarchy_type=hierarchy_type,
                boundary_type="DISTRICT",
            ),
            headers=auth_headers,
        )
        assert parent_resp.status_code == 201, f"Parent create failed: {parent_resp.text}"

        # 3. CREATE child relationship (BLOCK level — parent is the DISTRICT code)
        child_resp = requests.post(
            f"{base_url}/relationship",
            json=make_boundary_relation(
                code=child_code,
                hierarchy_type=hierarchy_type,
                boundary_type="BLOCK",
                parent=parent_code,
            ),
            headers=auth_headers,
        )
        assert child_resp.status_code == 201, f"Child create failed: {child_resp.text}"
        assert_gateway_headers(child_resp, gateway_headers_spec)

        child_rels = child_resp.json().get("relationship", [])
        assert any(r.get("parent") == parent_code for r in child_rels), (
            "Child relationship does not reference the parent code"
        )

    def test_create_then_update_relationship(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"

        # 1. CREATE
        create_resp = requests.post(
            f"{base_url}/relationship",
            json=make_boundary_relation(
                code=code,
                hierarchy_type=hierarchy_type,
                boundary_type="DISTRICT",
            ),
            headers=auth_headers,
        )
        assert create_resp.status_code == 201
        relationship_id = None
        for rel in create_resp.json().get("relationship", []):
            if rel.get("code") == code:
                relationship_id = rel.get("id")
                break

        if not relationship_id:
            pytest.skip("Service did not return relationship id — cannot test update")

        # 2. UPDATE
        update_resp = requests.put(
            f"{base_url}/relationship/{relationship_id}",
            json={
                "code": code,
                "hierarchyType": hierarchy_type,
                "boundaryType": "DISTRICT",
            },
            headers=auth_headers,
        )
        assert update_resp.status_code == 200, f"Update failed: {update_resp.text}"
        assert_gateway_headers(update_resp, gateway_headers_spec)
        assert_required_fields(update_resp.json(), ["relationship"])
