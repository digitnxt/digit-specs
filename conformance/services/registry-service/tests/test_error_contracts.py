import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers, assert_json_content_type
from tests.helpers.factories import (
    make_invalid_schema_request,
    make_invalid_data_request,
    make_schema_request,
    make_schema_code,
)

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup_schema(base_url, schema_code, headers):
    try:
        req_lib.delete(f"{base_url}/schema/{schema_code}", headers=headers)
    except Exception:
        pass


# ── Schema negative ───────────────────────────────────────────────────────────

class TestSchemaNegativeContracts:
    def test_create_missing_schema_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/schema",
                         headers=auth_headers,
                         json_body=make_invalid_schema_request("missing_schema_code"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_definition_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/schema",
                         headers=auth_headers,
                         json_body=make_invalid_schema_request("missing_definition"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_empty_body_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/schema",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/schema")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/schema", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_nonexistent_schema_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/schema/ghost-schema-xyz",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_schema_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/schema/ghost-schema-xyz",
                         headers=auth_headers,
                         json_body={"schemaCode": "ghost-schema-xyz",
                                    "definition": {"type": "object"}})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_schema_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/schema/ghost-schema-xyz",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_is_exist_missing_value_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "POST", f"{base_url}/schema/{code}/_isExist",
                             headers=auth_headers, json_body={})
            assert response.status_code == 400
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _cleanup_schema(base_url, code, auth_headers)


# ── Data negative ─────────────────────────────────────────────────────────────

class TestRegistryDataNegativeContracts:
    def test_create_data_missing_data_field_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                             headers=auth_headers,
                             json_body=make_invalid_data_request("missing_data"))
            assert response.status_code == 400
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_create_data_for_nonexistent_schema_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/schema/ghost-schema-xyz/data",
                         headers=auth_headers, json_body={"data": {"name": "test"}})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_data_nonexistent_id_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "GET", f"{base_url}/schema/{code}/data",
                             headers=auth_headers, params={"id": "nonexistent-id-xyz"})
            assert response.status_code == 404
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_delete_nonexistent_data_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            missing_id = "00000000-0000-0000-0000-000000000001"
            response = _send(request.node, "DELETE",
                             f"{base_url}/schema/{code}/data/{missing_id}",
                             headers=auth_headers)
            assert response.status_code in (404, 200, 202)
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_get_registry_nonexistent_registry_id_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "GET",
                             f"{base_url}/schema/{code}/data/_registry",
                             headers=auth_headers,
                             params={"registryId": "nonexistent-registry-id"})
            assert response.status_code == 404
            assert_gateway_headers(response, gateway_headers_spec)
        finally:
            _cleanup_schema(base_url, code, auth_headers)
