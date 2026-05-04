import pytest
import requests
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_service_response_headers,
    assert_required_fields,
    assert_field_types,
    assert_enum_values,
    assert_geometry_shape,
)
from tests.helpers.factories import (
    make_boundary_request,
    make_boundary_code,
    make_hierarchy_definition,
    make_boundary_relation,
)


class TestBoundarySearchContract:
    def test_search_with_valid_code_returns_boundary_response(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        # Create a boundary first so we have a known code to search
        code = make_boundary_code()
        create_resp = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        if create_resp.status_code != 201:
            pytest.skip("Could not create boundary — skipping search contract test")

        response = requests.get(
            f"{base_url}/boundaries",
            params={"codes": code},
            headers=auth_headers,
        )
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["boundary"])
        assert isinstance(body["boundary"], list)

    def test_boundary_item_has_required_fields(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        response = requests.get(
            f"{base_url}/boundaries",
            params={"codes": code},
            headers=auth_headers,
        )
        if response.status_code != 200:
            pytest.skip("Search returned non-200")

        for item in response.json().get("boundary", []):
            assert_required_fields(item, ["code"])
            assert_field_types(item, {"id": str, "code": str})
            if "geometry" in item and item["geometry"]:
                assert_geometry_shape(item["geometry"])


class TestBoundaryCreateContract:
    def test_create_returns_201_with_boundary_response(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["boundary"])
        assert isinstance(body["boundary"], list)
        assert len(body["boundary"]) >= 1

    def test_create_boundary_item_has_code(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        assert response.status_code == 201
        returned = response.json()["boundary"]
        codes_returned = [b["code"] for b in returned]
        assert code in codes_returned, f"Expected code '{code}' in response boundary list"

    def test_create_multiple_boundaries_in_one_request(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        codes = [make_boundary_code() for _ in range(3)]
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=codes),
            headers=auth_headers,
        )
        assert response.status_code == 201
        body = response.json()
        assert len(body["boundary"]) == 3


class TestBoundaryUpdateContract:
    def test_update_existing_boundary_returns_200(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        create_resp = requests.post(
            f"{base_url}/boundaries",
            json=make_boundary_request(codes=[code]),
            headers=auth_headers,
        )
        if create_resp.status_code != 201:
            pytest.skip("Could not create boundary for update test")

        boundary_id = create_resp.json()["boundary"][0].get("id")
        if not boundary_id:
            pytest.skip("Created boundary has no id — cannot test update")

        response = requests.put(
            f"{base_url}/boundaries/{boundary_id}",
            json={"code": code, "additionalAttributes": {"updated": True}},
            headers=auth_headers,
        )
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["boundary"])

    def test_update_nonexistent_boundary_returns_404(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.put(
            f"{base_url}/boundaries/nonexistent-id-xyz",
            json={"code": "DOES-NOT-EXIST"},
            headers=auth_headers,
        )
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestHierarchySearchContract:
    def test_search_hierarchy_returns_hierarchy_response(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(hierarchy_type=hierarchy_type),
            headers=auth_headers,
        )

        response = requests.get(
            f"{base_url}/hierarchy",
            params={"hierarchyType": hierarchy_type},
            headers=auth_headers,
        )
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["hierarchy"])
        assert isinstance(body["hierarchy"], list)

    def test_hierarchy_definition_item_shape(self, base_url, auth_headers, gateway_headers_spec):
        hierarchy_type = f"REVENUE-{make_boundary_code()}"
        requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(hierarchy_type=hierarchy_type),
            headers=auth_headers,
        )
        response = requests.get(
            f"{base_url}/hierarchy",
            params={"hierarchyType": hierarchy_type},
            headers=auth_headers,
        )
        if response.status_code != 200:
            pytest.skip("Hierarchy search returned non-200")

        for item in response.json().get("hierarchy", []):
            assert_field_types(item, {"id": str, "hierarchyType": str})
            if "boundaryHierarchy" in item:
                assert isinstance(item["boundaryHierarchy"], list)
                for bh in item["boundaryHierarchy"]:
                    assert_required_fields(bh, ["boundaryType"])


class TestHierarchyCreateContract:
    def test_create_hierarchy_returns_201(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.post(
            f"{base_url}/hierarchy",
            json=make_hierarchy_definition(),
            headers=auth_headers,
        )
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["hierarchy"])
        assert isinstance(body["hierarchy"], list)


class TestRelationshipSearchContract:
    def test_search_relationships_returns_boundary_search_response(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.get(f"{base_url}/relationship", headers=auth_headers)

        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["tenantBoundary"])
        assert isinstance(body["tenantBoundary"], list)

    def test_relationship_item_shape(self, base_url, auth_headers, gateway_headers_spec):
        response = requests.get(f"{base_url}/relationship", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json().get("tenantBoundary", []):
            if "hierarchyType" in item:
                assert_field_types(item, {"hierarchyType": str})
            if "boundary" in item:
                assert isinstance(item["boundary"], list)


class TestRelationshipCreateContract:
    def test_create_relationship_returns_201(self, base_url, auth_headers, gateway_headers_spec):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        response = requests.post(
            f"{base_url}/relationship",
            json=make_boundary_relation(
                code=code,
                hierarchy_type=hierarchy_type,
                boundary_type="DISTRICT",
            ),
            headers=auth_headers,
        )
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["relationship"])
        assert isinstance(body["relationship"], list)

    def test_created_relationship_has_required_fields(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_boundary_code()
        hierarchy_type = f"ADMIN-{make_boundary_code()}"
        response = requests.post(
            f"{base_url}/relationship",
            json=make_boundary_relation(
                code=code,
                hierarchy_type=hierarchy_type,
                boundary_type="DISTRICT",
            ),
            headers=auth_headers,
        )
        if response.status_code != 201:
            pytest.skip("Create relationship failed")

        for rel in response.json().get("relationship", []):
            assert_required_fields(rel, ["code", "hierarchyType", "boundaryType"])
            assert_field_types(rel, {"code": str, "hierarchyType": str, "boundaryType": str})
