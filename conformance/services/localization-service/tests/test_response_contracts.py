import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_service_response_headers,
    assert_field_types,
    assert_messages_response,
    assert_find_missing_response,
    assert_delete_response,
    assert_cache_bust_response,
)
from tests.helpers.factories import (
    make_module,
    make_msg_code,
    make_message,
    make_create_request,
    make_upsert_request,
    make_update_request,
    make_find_missing_request,
    LOCALES,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestSearchMessagesContract:
    def test_search_returns_200_with_messages_array(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/messages",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_messages_response(response.json())

    def test_search_by_module_filter(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        _send(request.node, "POST", f"{base_url}/messages",
              headers=auth_headers, json_body=make_create_request(module=module))

        response = _send(request.node, "GET", f"{base_url}/messages",
                         headers=auth_headers, params={"module": module})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert_messages_response(body)
        for msg in body["messages"]:
            if "module" in msg:
                assert msg["module"] == module

    def test_search_by_locale_filter(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        _send(request.node, "POST", f"{base_url}/messages",
              headers=auth_headers,
              json_body=make_create_request(module=module, locale="en_IN"))

        response = _send(request.node, "GET", f"{base_url}/messages",
                         headers=auth_headers,
                         params={"module": module, "locale": "en_IN"})
        assert response.status_code == 200
        body = response.json()
        assert_messages_response(body)
        for msg in body["messages"]:
            if "locale" in msg:
                assert msg["locale"] == "en_IN"

    def test_search_by_code_filter(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        _send(request.node, "POST", f"{base_url}/messages",
              headers=auth_headers,
              json_body=make_create_request(module=module, codes=[code]))

        response = _send(request.node, "GET", f"{base_url}/messages",
                         headers=auth_headers,
                         params={"module": module, "locale": "en_IN", "code": code})
        assert response.status_code == 200
        assert_messages_response(response.json())

    def test_search_result_message_field_types(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/messages",
                         headers=auth_headers)
        if response.status_code != 200:
            pytest.skip("Search returned non-200")
        for msg in response.json().get("messages", []):
            assert_field_types(msg, {
                "uuid": str, "module": str, "locale": str,
                "code": str, "message": str,
            })


class TestCreateMessagesContract:
    def test_create_returns_201_with_messages(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers, json_body=make_create_request())
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_messages_response(response.json())

    def test_create_single_message_echoes_code(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_msg_code()
        module = make_module()
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_create_request(module=module, codes=[code]))
        assert response.status_code == 201
        codes_returned = [m.get("code") for m in response.json()["messages"]]
        assert code in codes_returned, f"Expected code '{code}' in response"

    def test_create_multiple_messages(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        codes = [make_msg_code() for _ in range(3)]
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_create_request(codes=codes))
        assert response.status_code == 201
        assert len(response.json()["messages"]) == 3

    def test_created_messages_have_uuid(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers, json_body=make_create_request())
        assert response.status_code == 201
        for msg in response.json()["messages"]:
            assert "uuid" in msg, "Each created message must have a 'uuid' field"
            assert isinstance(msg["uuid"], str) and msg["uuid"]

    def test_create_messages_across_locales(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        messages = [make_message(module=module, locale=loc, code=f"{code}_{loc}")
                    for loc in ["en_IN", "hi_IN"]]
        response = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers, json_body={"messages": messages})
        assert response.status_code == 201
        assert len(response.json()["messages"]) == 2


class TestUpdateMessagesContract:
    def test_update_returns_200_with_messages(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        create_r = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_create_request(module=module, codes=[code]))
        if create_r.status_code != 201:
            pytest.skip("Create failed — cannot test update")

        created_msg = create_r.json()["messages"][0]
        msg_uuid = created_msg.get("uuid")
        if not msg_uuid:
            pytest.skip("Created message has no uuid — cannot test update")

        update_body = make_update_request(
            record_uuid=msg_uuid,
            locale="en_IN",
            module=module,
            message_updates=[{
                "uuid":    msg_uuid,
                "code":    code,
                "message": f"Updated text {make_msg_code()}",
            }],
        )
        response = _send(request.node, "PUT", f"{base_url}/messages",
                         headers=auth_headers, json_body=update_body)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_messages_response(response.json())

    def test_update_response_reflects_new_message_text(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        create_r = _send(request.node, "POST", f"{base_url}/messages",
                         headers=auth_headers,
                         json_body=make_create_request(module=module, codes=[code]))
        if create_r.status_code != 201:
            pytest.skip("Create failed")

        msg = create_r.json()["messages"][0]
        if not msg.get("uuid"):
            pytest.skip("No uuid on created message")

        new_text = f"Conformance updated {make_msg_code()}"
        update_body = make_update_request(
            record_uuid=msg["uuid"], locale="en_IN", module=module,
            message_updates=[{"uuid": msg["uuid"], "code": code, "message": new_text}],
        )
        response = _send(request.node, "PUT", f"{base_url}/messages",
                         headers=auth_headers, json_body=update_body)
        if response.status_code != 200:
            pytest.skip("Update returned non-200")
        texts = [m.get("message") for m in response.json()["messages"]]
        assert new_text in texts, f"Updated text not found in response: {texts}"


class TestUpsertMessagesContract:
    def test_upsert_returns_200_with_messages(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                         headers=auth_headers, json_body=make_upsert_request())
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_messages_response(response.json())

    def test_upsert_creates_when_not_exists(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        response = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                         headers=auth_headers,
                         json_body=make_upsert_request(module=module, codes=[code]))
        assert response.status_code == 200
        codes_returned = [m.get("code") for m in response.json()["messages"]]
        assert code in codes_returned

    def test_upsert_updates_existing_message(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        _send(request.node, "PUT", f"{base_url}/messages/_upsert",
              headers=auth_headers,
              json_body=make_upsert_request(module=module, codes=[code]))

        updated_msg = make_message(module=module, locale="en_IN", code=code,
                                   message="Upsert-updated text")
        response = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                         headers=auth_headers, json_body={"messages": [updated_msg]})
        assert response.status_code == 200
        assert_messages_response(response.json())


class TestDeleteMessagesContract:
    def test_delete_returns_200_with_success_boolean(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/messages",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_delete_response(response.json())

    def test_delete_success_field_is_boolean(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/messages",
                         headers=auth_headers)
        assert response.status_code == 200
        assert isinstance(response.json()["success"], bool)


class TestFindMissingMessagesContract:
    def test_find_missing_returns_200_with_map(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages/_missing",
                         headers=auth_headers, json_body=make_find_missing_request())
        assert response.status_code in (200, 404)
        assert_gateway_headers(response, gateway_headers_spec)
        if response.status_code == 200:
            assert_service_response_headers(response)
            assert_find_missing_response(response.json())

    def test_find_missing_with_specific_locales(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages/_missing",
                         headers=auth_headers,
                         json_body=make_find_missing_request(locales=["en_IN", "hi_IN"]))
        assert response.status_code in (200, 404)
        assert_gateway_headers(response, gateway_headers_spec)
        if response.status_code == 200:
            body = response.json()
            assert_find_missing_response(body)
            for locale in body.keys():
                assert locale in ["en_IN", "hi_IN"], \
                    f"Response locale '{locale}' was not in requested locales"

    def test_find_missing_response_values_are_string_arrays(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/messages/_missing",
                         headers=auth_headers, json_body={})
        if response.status_code != 200:
            pytest.skip("No messages in tenant — 404 expected")
        body = response.json()
        for locale, codes in body.items():
            assert isinstance(codes, list)
            for code in codes:
                assert isinstance(code, str), \
                    f"Missing code for locale '{locale}' must be a string"


class TestCacheBustContract:
    def test_cache_bust_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/cache/_bust",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_cache_bust_response(response.json())

    def test_cache_bust_success_is_true(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE", f"{base_url}/cache/_bust",
                         headers=auth_headers)
        assert response.status_code == 200
        assert response.json().get("success") is True
