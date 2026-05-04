import pytest
import requests
from tests.helpers.validators import assert_error_schema, assert_gateway_headers
from tests.helpers.factories import (
    make_invalid_boundary_request,
    make_invalid_hierarchy_request,
    make_invalid_relation_request,
    make_boundary_code,
)

# Derived from common.yaml Error schema; 400 responses return an array of errors
SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}

ERROR_ARRAY_SCHEMA = {
    "type": "array",
    "items": SINGLE_ERROR_SCHEMA,
}


class TestBoundaryNegativeContracts:
    def test_create_missing_boundary_array_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_invalid_boundary_request("missing_required"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        if isinstance(body, list):
            assert_error_schema(body, ERROR_ARRAY_SCHEMA)

    def test_create_empty_boundary_array_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_invalid_boundary_request("empty_boundary_array"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_boundary_missing_code_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_invalid_boundary_request("missing_code"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_boundary_wrong_type_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/boundaries",
            json=make_invalid_boundary_request("wrong_type"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_missing_codes_param_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        # codes is required on GET /boundaries
        response = requests.get(f"{base_url}/boundaries", headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, base_url, gateway_headers_spec):
        response = requests.get(
            f"{base_url}/boundaries", params={"codes": "SOME-CODE"}
        )
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, base_url, auth_headers, gateway_headers_spec):
        bad_headers = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = requests.get(
            f"{base_url}/boundaries",
            params={"codes": "SOME-CODE"},
            headers=bad_headers,
        )
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_boundary_returns_404(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.put(
            f"{base_url}/boundaries/nonexistent-id-000",
            json={"code": "GHOST"},
            headers=auth_headers,
        )
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestHierarchyNegativeContracts:
    def test_create_hierarchy_empty_body_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/hierarchy",
            json=make_invalid_hierarchy_request("missing_required"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_hierarchy_missing_required_param_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        # hierarchyType is required on GET /hierarchy
        response = requests.get(f"{base_url}/hierarchy", headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_hierarchy_returns_404(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.put(
            f"{base_url}/hierarchy/nonexistent-id-000",
            json={"boundaryHierarchy": {"hierarchyType": "GHOST", "boundaryHierarchy": []}},
            headers=auth_headers,
        )
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestRelationshipNegativeContracts:
    def test_create_relationship_missing_required_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/relationship",
            json=make_invalid_relation_request("missing_required"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_relationship_missing_hierarchy_type_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/relationship",
            json=make_invalid_relation_request("missing_hierarchy_type"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_relationship_missing_boundary_type_returns_400(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.post(
            f"{base_url}/relationship",
            json=make_invalid_relation_request("missing_boundary_type"),
            headers=auth_headers,
        )
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_relationship_returns_404(
        self, base_url, auth_headers, gateway_headers_spec
    ):
        response = requests.put(
            f"{base_url}/relationship/nonexistent-id-000",
            json={
                "code": "GHOST",
                "hierarchyType": "ADMIN",
                "boundaryType": "DISTRICT",
            },
            headers=auth_headers,
        )
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
