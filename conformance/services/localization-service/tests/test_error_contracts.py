import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_gateway_headers
from tests.helpers.factories import (
    make_create_request,
    make_upsert_request,
    make_invalid_create_request,
    make_invalid_update_request,
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


class TestCreateMessagesNegativeContracts:
    def test_create_empty_messages_array_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_create_request("empty_messages"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_messages_key_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_create_request("missing_messages"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_wrong_type_for_messages_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_create_request("wrong_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_message_missing_locale_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_create_request("missing_locale"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_message_missing_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_create_request("missing_code"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_message_missing_module_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_create_request("missing_module"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         json_body=make_create_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=bad, json_body=make_create_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestUpdateMessagesNegativeContracts:
    def test_update_missing_messages_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_update_request("missing_messages"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_empty_messages_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_invalid_update_request("empty_messages"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages",
                         json_body=make_invalid_update_request("missing_messages"))
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestUpsertMessagesNegativeContracts:
    def test_upsert_empty_messages_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                         headers=auth_headers, json_body={"messages": []})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_upsert_missing_messages_key_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_upsert_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                         json_body=make_upsert_request())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestSearchMessagesNegativeContracts:
    def test_search_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/messages")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/messages", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestDeleteMessagesNegativeContracts:
    def test_delete_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/messages")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestFindMissingNegativeContracts:
    def test_find_missing_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages/_missing",
                         json_body={})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_find_missing_invalid_locales_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages/_missing",
                         headers=auth_headers,
                         json_body={"locales": "en_IN"})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)


class TestCacheBustNegativeContracts:
    def test_cache_bust_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/cache/_bust")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_cache_bust_invalid_token_returns_401(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "DELETE", f"{base_url}/cache/_bust", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)
