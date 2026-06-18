import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_boundary_code,
    make_boundary_request,
    make_hierarchy_definition,
    make_boundary_relation,
)
from tests.helpers.validators import assert_gateway_headers, assert_required_fields

# NOTE: The boundary service has no DELETE endpoints.
# Created resources persist after the test run.


def _send(node, method, url, headers=None, json_body=None, params=None):
    """Prepare, attach cURL (for HTML report), then send."""
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestBoundaryCreateAndSearch:
    def test_create_then_search_by_code(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()

        # 1. CREATE
        r = _send(request.node, "POST", f"{base_url}/boundaries",
                  headers=auth_headers, json_body=make_boundary_request(codes=[code]))
        assert r.status_code == 201, f"Create failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert any(b["code"] == code for b in r.json()["boundary"])

        # 2. SEARCH
        r = _send(request.node, "GET", f"{base_url}/boundaries",
                  headers=auth_headers, params={"codes": code})
        assert r.status_code == 200, f"Search failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert any(b["code"] == code for b in r.json()["boundary"]), \
            f"Code '{code}' not returned in search"

    def test_create_then_update_boundary(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()

        r = _send(request.node, "POST", f"{base_url}/boundaries",
                  headers=auth_headers, json_body=make_boundary_request(codes=[code]))
        assert r.status_code == 201, f"Create failed: {r.text}"
        boundary_id = r.json()["boundary"][0].get("id")
        if not boundary_id:
            pytest.skip("Service did not return boundary id — cannot test update")

        r = _send(request.node, "PUT", f"{base_url}/boundaries/{boundary_id}",
                  headers=auth_headers,
                  json_body={"code": code, "additionalAttributes": {"conformance": "updated"}})
        assert r.status_code == 200, f"Update failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_required_fields(r.json(), ["boundary"])

    def test_create_batch_then_search_all(self, request, base_url, auth_headers, gateway_headers_spec):
        codes = [make_boundary_code() for _ in range(3)]

        r = _send(request.node, "POST", f"{base_url}/boundaries",
                  headers=auth_headers, json_body=make_boundary_request(codes=codes))
        assert r.status_code == 201

        r = _send(request.node, "GET", f"{base_url}/boundaries",
                  headers=auth_headers, params=[("codes", c) for c in codes])
        assert r.status_code == 200
        returned = {b["code"] for b in r.json().get("boundary", [])}
        for code in codes:
            assert code in returned, f"Code '{code}' missing from batch search"


class TestHierarchyCreateAndSearch:
    def test_create_then_search_hierarchy(self, request, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"ADMIN-{make_boundary_code()}"

        r = _send(request.node, "POST", f"{base_url}/hierarchy",
                  headers=auth_headers,
                  json_body=make_hierarchy_definition(hierarchy_type=hierarchy_type))
        assert r.status_code == 201, f"Create failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_required_fields(r.json(), ["hierarchy"])

        r = _send(request.node, "GET", f"{base_url}/hierarchy",
                  headers=auth_headers, params={"hierarchyType": hierarchy_type})
        assert r.status_code == 200, f"Search failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert any(h.get("hierarchyType") == hierarchy_type for h in r.json()["hierarchy"]), \
            f"Hierarchy type '{hierarchy_type}' not found after creation"

    def test_create_then_update_hierarchy(self, request, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"REVENUE-{make_boundary_code()}"

        r = _send(request.node, "POST", f"{base_url}/hierarchy",
                  headers=auth_headers,
                  json_body=make_hierarchy_definition(hierarchy_type=hierarchy_type))
        assert r.status_code == 201

        hierarchy_id = next(
            (h.get("id") for h in r.json().get("hierarchy", [])
             if h.get("hierarchyType") == hierarchy_type),
            None
        )
        if not hierarchy_id:
            pytest.skip("Service did not return hierarchy id — cannot test update")

        updated_payload = {
            "hierarchy": {
                "hierarchyType": hierarchy_type,
                "boundaryHierarchy": [
                    {"boundaryType": "STATE",    "parentBoundaryType": None,       "active": True},
                    {"boundaryType": "DISTRICT", "parentBoundaryType": "STATE",    "active": True},
                    {"boundaryType": "BLOCK",    "parentBoundaryType": "DISTRICT", "active": True},
                ],
            }
        }
        r = _send(request.node, "PUT", f"{base_url}/hierarchy/{hierarchy_id}",
                  headers=auth_headers, json_body=updated_payload)
        assert r.status_code == 200, f"Update failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_required_fields(r.json(), ["hierarchy"])


class TestRelationshipCreateAndSearch:
    def test_create_then_search_relationship(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"

        # 1. CREATE HIERARCHY
        _send(request.node, "POST", f"{base_url}/hierarchy",
              headers=auth_headers,
              json_body=make_hierarchy_definition(hierarchy_type=hierarchy_type))

        # 2. CREATE BOUNDARY ENTITY (REQUIRED BEFORE RELATIONSHIP!)
        _send(request.node, "POST", f"{base_url}/boundaries",
              headers=auth_headers,
              json_body=make_boundary_request(codes=[code]))

        # 3. CREATE RELATIONSHIP
        r = _send(request.node, "POST", f"{base_url}/relationship",
                  headers=auth_headers,
                  json_body=make_boundary_relation(code=code, hierarchy_type=hierarchy_type,
                                                   boundary_type="DISTRICT"))
        assert r.status_code == 201, f"Create failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_required_fields(r.json(), ["relationship"])

        # 4. SEARCH RELATIONSHIP
        r = _send(request.node, "GET", f"{base_url}/relationship",
                  headers=auth_headers,
                  params={"hierarchyType": hierarchy_type, "codes": code})
        assert r.status_code == 200, f"Search failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_required_fields(r.json(), ["tenantBoundary"])

    def test_create_parent_child_relationship(self, request, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        parent_code = make_boundary_code()
        child_code  = make_boundary_code()

        # 1. CREATE HIERARCHY
        _send(request.node, "POST", f"{base_url}/hierarchy",
              headers=auth_headers,
              json_body=make_hierarchy_definition(
                  hierarchy_type=hierarchy_type,
                  boundary_types=[
                      {"boundaryType": "DISTRICT", "parentBoundaryType": None,      "active": True},
                      {"boundaryType": "BLOCK",    "parentBoundaryType": "DISTRICT", "active": True},
                  ],
              ))

        # 2. CREATE BOUNDARY ENTITIES (BOTH PARENT AND CHILD!)
        _send(request.node, "POST", f"{base_url}/boundaries",
              headers=auth_headers,
              json_body=make_boundary_request(codes=[parent_code, child_code]))

        # 3. CREATE PARENT RELATIONSHIP
        r = _send(request.node, "POST", f"{base_url}/relationship",
                  headers=auth_headers,
                  json_body=make_boundary_relation(code=parent_code,
                                                   hierarchy_type=hierarchy_type,
                                                   boundary_type="DISTRICT"))
        assert r.status_code == 201, f"Parent create failed: {r.text}"

        # 4. CREATE CHILD RELATIONSHIP
        r = _send(request.node, "POST", f"{base_url}/relationship",
                  headers=auth_headers,
                  json_body=make_boundary_relation(code=child_code,
                                                   hierarchy_type=hierarchy_type,
                                                   boundary_type="BLOCK",
                                                   parent=parent_code))
        assert r.status_code == 201, f"Child create failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert any(rel.get("parent") == parent_code for rel in r.json().get("relationship", [])), \
            "Child relationship does not reference the parent code"

    def test_create_then_update_relationship(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"

        # 1. CREATE HIERARCHY
        _send(request.node, "POST", f"{base_url}/hierarchy",
              headers=auth_headers,
              json_body=make_hierarchy_definition(hierarchy_type=hierarchy_type))

        # 2. CREATE BOUNDARY ENTITY (REQUIRED BEFORE RELATIONSHIP!)
        _send(request.node, "POST", f"{base_url}/boundaries",
              headers=auth_headers,
              json_body=make_boundary_request(codes=[code]))

        # 3. CREATE RELATIONSHIP
        r = _send(request.node, "POST", f"{base_url}/relationship",
                  headers=auth_headers,
                  json_body=make_boundary_relation(code=code, hierarchy_type=hierarchy_type,
                                                   boundary_type="DISTRICT"))
        assert r.status_code == 201

        relationship_id = next(
            (rel.get("id") for rel in r.json().get("relationship", []) if rel.get("code") == code),
            None
        )
        if not relationship_id:
            pytest.skip("Service did not return relationship id — cannot test update")

        # 4. UPDATE RELATIONSHIP
        r = _send(request.node, "PUT", f"{base_url}/relationship/{relationship_id}",
                  headers=auth_headers,
                  json_body={"code": code, "hierarchyType": hierarchy_type,
                              "boundaryType": "DISTRICT"})
        assert r.status_code == 200, f"Update failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_required_fields(r.json(), ["relationship"])
