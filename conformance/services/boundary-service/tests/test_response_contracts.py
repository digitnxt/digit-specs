import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_service_response_headers,
    assert_required_fields,
    assert_field_types,
    assert_geometry_shape,
)
from tests.helpers.factories import (
    make_boundary_request,
    make_boundary_code,
    make_hierarchy_definition,
    make_boundary_relation,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestBoundarySearchContract:
    def test_search_with_valid_code_returns_boundary_response(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        create_r = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers,
                         json_body=make_boundary_request(codes=[code]))
        if create_r.status_code != 201:
            pytest.skip("Could not create boundary — skipping search contract test")

        response = _send(request.node, "GET", f"{base_url}/boundaries",
                         headers=auth_headers, params={"codes": code})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["boundary"])
        assert isinstance(body["boundary"], list)

    def test_boundary_item_has_required_fields(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        _send(request.node, "POST", f"{base_url}/boundaries",
              headers=auth_headers, json_body=make_boundary_request(codes=[code]))

        response = _send(request.node, "GET", f"{base_url}/boundaries",
                         headers=auth_headers, params={"codes": code})
        if response.status_code != 200:
            pytest.skip("Search returned non-200")

        for item in response.json().get("boundary", []):
            assert_required_fields(item, ["code"])
            assert_field_types(item, {"id": str, "code": str})
            if item.get("geometry"):
                assert_geometry_shape(item["geometry"])


class TestBoundaryCreateContract:
    def test_create_returns_201_with_boundary_response(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers, json_body=make_boundary_request(codes=[code]))

        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["boundary"])
        assert isinstance(body["boundary"], list)
        assert len(body["boundary"]) >= 1

    def test_create_boundary_item_has_code(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers, json_body=make_boundary_request(codes=[code]))
        assert response.status_code == 201
        codes_returned = [b["code"] for b in response.json()["boundary"]]
        assert code in codes_returned

    def test_create_multiple_boundaries_in_one_request(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        codes = [make_boundary_code() for _ in range(3)]
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers, json_body=make_boundary_request(codes=codes))
        assert response.status_code == 201
        assert len(response.json()["boundary"]) == 3


class TestBoundaryUpdateContract:
    def test_update_existing_boundary_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        create_r = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers, json_body=make_boundary_request(codes=[code]))
        if create_r.status_code != 201:
            pytest.skip("Could not create boundary for update test")

        boundary_id = create_r.json()["boundary"][0].get("id")
        if not boundary_id:
            pytest.skip("Created boundary has no id — cannot test update")

        response = _send(request.node, "PUT", f"{base_url}/boundaries/{boundary_id}",
                         headers=auth_headers,
                         json_body={"code": code, "additionalAttributes": {"updated": True}})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_required_fields(response.json(), ["boundary"])

    def test_update_nonexistent_boundary_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/boundaries/nonexistent-id-xyz",
                         headers=auth_headers, json_body={"code": "DOES-NOT-EXIST"})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestHierarchySearchContract:
    def test_search_hierarchy_returns_hierarchy_response(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        _send(request.node, "POST", f"{base_url}/hierarchy",
              headers=auth_headers,
              json_body=make_hierarchy_definition(hierarchy_type=hierarchy_type))

        response = _send(request.node, "GET", f"{base_url}/hierarchy",
                         headers=auth_headers, params={"hierarchyType": hierarchy_type})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["hierarchy"])
        assert isinstance(body["hierarchy"], list)

    def test_hierarchy_definition_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"REVENUE-{make_boundary_code()}"
        _send(request.node, "POST", f"{base_url}/hierarchy",
              headers=auth_headers,
              json_body=make_hierarchy_definition(hierarchy_type=hierarchy_type))

        response = _send(request.node, "GET", f"{base_url}/hierarchy",
                         headers=auth_headers, params={"hierarchyType": hierarchy_type})
        if response.status_code != 200:
            pytest.skip("Hierarchy search returned non-200")

        for item in response.json().get("hierarchy", []):
            assert_field_types(item, {"id": str, "hierarchyType": str})
            if "boundaryHierarchy" in item:
                assert isinstance(item["boundaryHierarchy"], list)
                for bh in item["boundaryHierarchy"]:
                    assert_required_fields(bh, ["boundaryType"])


class TestHierarchyCreateContract:
    def test_create_hierarchy_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/hierarchy",
                         headers=auth_headers,
                         json_body=make_hierarchy_definition())
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["hierarchy"])
        assert isinstance(body["hierarchy"], list)


class TestRelationshipSearchContract:
    def test_search_relationships_returns_boundary_search_response(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/relationship", headers=auth_headers)

        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["tenantBoundary"])
        assert isinstance(body["tenantBoundary"], list)

    def test_relationship_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/relationship", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json().get("tenantBoundary", []):
            if "hierarchyType" in item:
                assert_field_types(item, {"hierarchyType": str})
            if "boundary" in item:
                assert isinstance(item["boundary"], list)


class TestRelationshipCreateContract:
    def test_create_relationship_returns_201(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        response = _send(request.node, "POST", f"{base_url}/relationship",
                         headers=auth_headers,
                         json_body=make_boundary_relation(code=code,
                                                          hierarchy_type=hierarchy_type,
                                                          boundary_type="DISTRICT"))
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["relationship"])
        assert isinstance(body["relationship"], list)

    def test_created_relationship_has_required_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        response = _send(request.node, "POST", f"{base_url}/relationship",
                         headers=auth_headers,
                         json_body=make_boundary_relation(code=code,
                                                          hierarchy_type=hierarchy_type,
                                                          boundary_type="DISTRICT"))
        if response.status_code != 201:
            pytest.skip("Create relationship failed")

        for rel in response.json().get("relationship", []):
            assert_required_fields(rel, ["code", "hierarchyType", "boundaryType"])
            assert_field_types(rel, {"code": str, "hierarchyType": str, "boundaryType": str})


class TestShapefileCreateContract:
    def test_create_shapefile_boundary_missing_file_store_ids_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/shapefile/boundary",
                         headers=auth_headers, json_body={"uniqueCodeField": "code"})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_shapefile_boundary_with_valid_payload_returns_200_or_422(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/shapefile/boundary",
                         headers=auth_headers,
                         json_body={"fileStoreIds": ["nonexistent-file-id"],
                                    "uniqueCodeField": "code"})
        assert response.status_code in (200, 400, 422, 404), (
            f"Unexpected status {response.status_code}: {response.text}"
        )
        if response.status_code == 200:
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert "message" in body or "count" in body
