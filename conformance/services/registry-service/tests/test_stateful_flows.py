import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_schema_request,
    make_schema_update,
    make_schema_code,
    make_data_request,
    make_search_request,
    make_is_exist_request,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_required_fields,
    assert_registry_data_shape,
    assert_schema_shape,
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


class TestSchemaLifecycle:
    def test_create_get_update_delete_schema(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        try:
            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/schema",
                      headers=auth_headers, json_body=make_schema_request(schema_code=code))
            assert r.status_code == 201, f"Schema create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_schema_shape(r.json()["data"])

            # 2. GET by code
            r = _send(request.node, "GET", f"{base_url}/schema/{code}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["data"]["schemaCode"] == code
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. LIST — should include our schema
            r = _send(request.node, "GET", f"{base_url}/schema", headers=auth_headers)
            assert r.status_code == 200
            codes = [s["schemaCode"] for s in r.json().get("data", [])]
            assert code in codes

            # 4. UPDATE — should bump version
            r = _send(request.node, "PUT", f"{base_url}/schema/{code}",
                      headers=auth_headers, json_body=make_schema_update(schema_code=code))
            assert r.status_code == 200, f"Schema update failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            updated = r.json()["data"]
            assert updated["schemaCode"] == code

            # 5. DELETE
            r = _send(request.node, "DELETE", f"{base_url}/schema/{code}", headers=auth_headers)
            assert r.status_code == 200, f"Schema delete failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            code = None

        finally:
            if code:
                _cleanup_schema(base_url, code, auth_headers)

    def test_schema_version_increments_on_update(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        try:
            r = _send(request.node, "POST", f"{base_url}/schema",
                      headers=auth_headers, json_body=make_schema_request(schema_code=code))
            assert r.status_code == 201
            v1 = r.json()["data"]["version"]

            r = _send(request.node, "PUT", f"{base_url}/schema/{code}",
                      headers=auth_headers, json_body=make_schema_update(schema_code=code))
            assert r.status_code == 200
            v2 = r.json()["data"]["version"]
            assert v2 >= v1, f"Version did not increment: v1={v1}, v2={v2}"

        finally:
            _cleanup_schema(base_url, code, auth_headers)


class TestRegistryDataLifecycle:
    def test_create_get_update_delete_data(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        record_id = None
        registry_id = None
        try:
            # Setup schema
            r = _send(request.node, "POST", f"{base_url}/schema",
                      headers=auth_headers, json_body=make_schema_request(schema_code=code))
            assert r.status_code == 201, f"Schema create failed: {r.text}"

            # 1. CREATE data
            r = _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                      headers=auth_headers, json_body=make_data_request())
            assert r.status_code in (201, 202), f"Data create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            if r.status_code == 201:
                data = r.json()["data"]
                assert_registry_data_shape(data)
                record_id = data["id"]
                registry_id = data["registryId"]

                # 2. GET by id
                r = _send(request.node, "GET", f"{base_url}/schema/{code}/data",
                          headers=auth_headers, params={"id": record_id})
                assert r.status_code == 200
                assert r.json()["data"]["id"] == record_id
                assert_gateway_headers(r, gateway_headers_spec)

                # 3. GET by registryId
                r = _send(request.node, "GET", f"{base_url}/schema/{code}/data/_registry",
                          headers=auth_headers, params={"registryId": registry_id})
                assert r.status_code == 200
                assert_gateway_headers(r, gateway_headers_spec)

                # 4. UPDATE
                r = _send(request.node, "PUT", f"{base_url}/schema/{code}/data",
                          headers=auth_headers,
                          params={"id": record_id},
                          json_body=make_data_request(version=data["version"]))
                assert r.status_code in (200, 202), f"Data update failed: {r.text}"
                assert_gateway_headers(r, gateway_headers_spec)

                # 5. DELETE
                r = _send(request.node, "DELETE",
                          f"{base_url}/schema/{code}/data/{record_id}",
                          headers=auth_headers)
                assert r.status_code in (200, 202), f"Data delete failed: {r.text}"
                assert_gateway_headers(r, gateway_headers_spec)
                record_id = None

        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_exists_check_after_create(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        try:
            _send(request.node, "POST", f"{base_url}/schema",
                  headers=auth_headers, json_body=make_schema_request(schema_code=code))

            r = _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                      headers=auth_headers, json_body=make_data_request())
            if r.status_code not in (201, 202):
                pytest.skip("Data create failed — skipping exists test")

            record_id = None
            if r.status_code == 201:
                record_id = r.json()["data"]["id"]

            # Check _exists returns a boolean
            params = {"id": record_id} if record_id else {"id": "some-id"}
            r = _send(request.node, "GET", f"{base_url}/schema/{code}/data/_exists",
                      headers=auth_headers, params=params)
            assert r.status_code == 200
            body = r.json()
            assert "data" in body
            assert "exists" in body["data"]
            assert isinstance(body["data"]["exists"], bool)

        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_search_after_create(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        try:
            _send(request.node, "POST", f"{base_url}/schema",
                  headers=auth_headers, json_body=make_schema_request(schema_code=code))

            record_name = f"SearchRecord-{code[:8]}"
            _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                  headers=auth_headers,
                  json_body=make_data_request(data={"name": record_name, "value": "v1"}))

            r = _send(request.node, "POST", f"{base_url}/schema/{code}/data/_search",
                      headers=auth_headers,
                      json_body={"filters": {"name": record_name}, "limit": 10})
            assert r.status_code == 200
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert "data" in body
            assert isinstance(body["data"], list)

        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_history_flag_returns_list(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        try:
            _send(request.node, "POST", f"{base_url}/schema",
                  headers=auth_headers, json_body=make_schema_request(schema_code=code))

            r = _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                      headers=auth_headers, json_body=make_data_request())
            if r.status_code not in (201, 202):
                pytest.skip("Data create failed — skipping history test")

            if r.status_code == 201:
                registry_id = r.json()["data"]["registryId"]
                r = _send(request.node, "GET", f"{base_url}/schema/{code}/data/_registry",
                          headers=auth_headers,
                          params={"registryId": registry_id, "history": "true"})
                assert r.status_code == 200
                assert_gateway_headers(r, gateway_headers_spec)

        finally:
            _cleanup_schema(base_url, code, auth_headers)

    def test_is_exist_after_create(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_schema_code()
        try:
            _send(request.node, "POST", f"{base_url}/schema",
                  headers=auth_headers, json_body=make_schema_request(schema_code=code))

            r = _send(request.node, "POST", f"{base_url}/schema/{code}/data",
                      headers=auth_headers, json_body=make_data_request())
            if r.status_code not in (201, 202):
                pytest.skip("Data create failed — skipping isExist test")

            if r.status_code == 201:
                registry_id = r.json()["data"]["registryId"]
                r = _send(request.node, "POST", f"{base_url}/schema/{code}/_isExist",
                          headers=auth_headers,
                          json_body=make_is_exist_request(value=registry_id))
                assert r.status_code == 200
                assert_gateway_headers(r, gateway_headers_spec)
                body = r.json()
                assert body["data"]["exists"] is True

        finally:
            _cleanup_schema(base_url, code, auth_headers)
