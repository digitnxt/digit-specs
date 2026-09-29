import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_error_schema, assert_gateway_headers
from tests.helpers.factories import (
    make_invalid_boundary_request,
    make_invalid_hierarchy_request,
    make_invalid_relation_request,
)

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}
ERROR_ARRAY_SCHEMA = {"type": "array", "items": SINGLE_ERROR_SCHEMA}


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestBoundaryNegativeContracts:
    def test_create_missing_boundary_array_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers,
                         json_body=make_invalid_boundary_request("missing_required"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        if isinstance(body, list):
            assert_error_schema(body, ERROR_ARRAY_SCHEMA)

    def test_create_empty_boundary_array_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers,
                         json_body=make_invalid_boundary_request("empty_boundary_array"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_boundary_missing_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers,
                         json_body=make_invalid_boundary_request("missing_code"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_boundary_wrong_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/boundaries",
                         headers=auth_headers,
                         json_body=make_invalid_boundary_request("wrong_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_missing_codes_param_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/boundaries", headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/boundaries",
                         params={"codes": "SOME-CODE"})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/boundaries",
                         headers=bad, params={"codes": "SOME-CODE"})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_boundary_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/boundaries/{uuid.uuid4()}",
                         headers=auth_headers, json_body={"code": "GHOST"})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestHierarchyNegativeContracts:
    def test_create_hierarchy_empty_body_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/hierarchy",
                         headers=auth_headers,
                         json_body=make_invalid_hierarchy_request("missing_required"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_hierarchy_missing_required_param_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/hierarchy", headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_hierarchy_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/hierarchy/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body={"hierarchy": {"hierarchyType": "GHOST",
                                                   "boundaryHierarchy": []}})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# class TestShapefileNegativeContracts:
#     def test_create_shapefile_missing_required_returns_400(
#         self, request, base_url, auth_headers, gateway_headers_spec
#     ):
#         response = _send(request.node, "POST", f"{base_url}/shapefile/boundary",
#                          headers=auth_headers, json_body={})
#         assert response.status_code == 400
#         assert_gateway_headers(response, gateway_headers_spec)
#
#     def test_create_shapefile_empty_file_store_ids_returns_400(
#         self, request, base_url, auth_headers, gateway_headers_spec
#     ):
#         response = _send(request.node, "POST", f"{base_url}/shapefile/boundary",
#                          headers=auth_headers, json_body={"fileStoreIds": []})
#         assert response.status_code == 400
#         assert_gateway_headers(response, gateway_headers_spec)
#
#     def test_create_shapefile_missing_auth_returns_401(
#         self, request, base_url, gateway_headers_spec
#     ):
#         response = _send(request.node, "POST", f"{base_url}/shapefile/boundary",
#                          json_body={"fileStoreIds": ["fake-id"]})
#         assert response.status_code == 401
#         assert_gateway_headers(response, gateway_headers_spec)


class TestRelationshipNegativeContracts:
    def test_create_relationship_missing_required_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/relationship",
                         headers=auth_headers,
                         json_body=make_invalid_relation_request("missing_required"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_relationship_missing_hierarchy_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/relationship",
                         headers=auth_headers,
                         json_body=make_invalid_relation_request("missing_hierarchy_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_relationship_missing_boundary_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/relationship",
                         headers=auth_headers,
                         json_body=make_invalid_relation_request("missing_boundary_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_relationship_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/relationship/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body={"code": "GHOST", "hierarchyType": "ADMIN",
                                    "boundaryType": "DISTRICT"})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
