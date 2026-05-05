import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_service_response_headers,
    assert_required_fields,
    assert_field_types,
    assert_registry_data_shape,
    assert_schema_shape,
)
from tests.helpers.factories import (
    make_schema_request,
    make_schema_code,
    make_data_request,
    make_search_request,
    make_is_exist_request,
)


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


# ── Schema ────────────────────────────────────────────────────────────────────

class TestSchemaCreateContract:
    def test_create_returns_201_with_schema_envelope(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        response = _send(request.node, "POST", f"{base_url}/schema",
                         headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            assert response.status_code == 201, f"Create failed: {response.text}"
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert "data" in body, "Response missing 'data' envelope"
            assert_schema_shape(body["data"])
            assert body["data"]["schemaCode"] == code
        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_created_schema_version_is_1(self, request, base_url, auth_headers, gateway_headers_spec):
        code = make_schema_code()
        response = _send(request.node, "POST", f"{base_url}/schema",
                         headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            if response.status_code != 201:
                pytest.skip("Schema creation failed")
            assert response.json()["data"]["version"] == 1
            assert response.json()["data"]["isLatest"] is True
        finally:
            _cleanup_schema(base_url, code, auth_headers)


class TestSchemaListContract:
    def test_list_returns_200_with_data_array(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/schema", headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert "data" in body
        assert isinstance(body["data"], list)

    def test_list_schema_items_have_required_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/schema", headers=auth_headers)
        assert response.status_code == 200
        for item in response.json().get("data", []):
            assert_schema_shape(item)


class TestSchemaGetByCodeContract:
    def test_get_by_code_returns_schema_envelope(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "GET", f"{base_url}/schema/{code}",
                             headers=auth_headers)
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert "data" in body
            assert body["data"]["schemaCode"] == code
        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_get_nonexistent_schema_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/schema/does-not-exist-xyz",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── Registry Data ─────────────────────────────────────────────────────────────

class TestRegistryDataCreateContract:
    def test_create_data_returns_201_or_202(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                             headers=auth_headers, json_body=make_data_request())
            assert response.status_code in (201, 202), f"Create data failed: {response.text}"
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            if response.status_code == 201:
                body = response.json()
                assert "data" in body
                assert_registry_data_shape(body["data"])
                assert body["data"]["schemaCode"] == code
        finally:
            _cleanup_schema(base_url, code, auth_headers)


class TestRegistryDataSearchContract:
    def test_search_returns_200_with_data_array(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "POST", f"{base_url}/schema/{code}/data/_search",
                             headers=auth_headers, json_body=make_search_request())
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_service_response_headers(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert "data" in body
            assert isinstance(body["data"], list)
        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_search_with_filters_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "POST", f"{base_url}/schema/{code}/data/_search",
                             headers=auth_headers,
                             json_body={"filters": {"name": "nonexistent"}, "limit": 10})
            assert response.status_code == 200
            assert isinstance(response.json().get("data", []), list)
        finally:
            _cleanup_schema(base_url, code, auth_headers)


class TestRegistryDataExistsContract:
    def test_exists_returns_boolean_flag(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "GET", f"{base_url}/schema/{code}/data/_exists",
                             headers=auth_headers, params={"id": "nonexistent-id"})
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert "data" in body
            assert "exists" in body["data"]
            assert isinstance(body["data"]["exists"], bool)
        finally:
            _cleanup_schema(base_url, code, auth_headers)


class TestSchemaIsExistContract:
    def test_is_exist_returns_boolean_flag(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        _send(request.node, "POST", f"{base_url}/schema",
              headers=auth_headers, json_body=make_schema_request(schema_code=code))
        try:
            response = _send(request.node, "POST", f"{base_url}/schema/{code}/_isExist",
                             headers=auth_headers,
                             json_body=make_is_exist_request(value="nonexistent-value"))
            assert response.status_code == 200
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)
            body = response.json()
            assert "data" in body
            assert "exists" in body["data"]
            assert isinstance(body["data"]["exists"], bool)
            assert body["data"]["exists"] is False
        finally:
            _cleanup_schema(base_url, code, auth_headers)
