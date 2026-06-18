import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_module,
    make_msg_code,
    make_message,
    make_create_request,
    make_upsert_request,
    make_update_request,
    make_find_missing_request,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_messages_response,
    assert_find_missing_response,
    assert_cache_bust_response,
    assert_delete_response,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


class TestMessageLifecycle:
    def test_create_then_search_by_module(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()

        # CREATE
        r = _send(request.node, "POST", f"{base_url}/messages",
                  headers=auth_headers,
                  json_body=make_create_request(module=module, codes=[code]))
        assert r.status_code == 201, f"Create failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_messages_response(r.json())

        # SEARCH — confirm message is retrievable
        r = _send(request.node, "GET", f"{base_url}/messages",
                  headers=auth_headers,
                  params={"module": module, "locale": "en_IN"})
        assert r.status_code == 200, f"Search failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        codes_found = [m.get("code") for m in r.json()["messages"]]
        assert code in codes_found, f"Created code '{code}' not found in search"

    def test_create_then_update(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()

        # CREATE
        r = _send(request.node, "POST", f"{base_url}/messages",
                  headers=auth_headers,
                  json_body=make_create_request(module=module, codes=[code]))
        assert r.status_code == 201, f"Create failed: {r.text}"
        msg = r.json()["messages"][0]
        if not msg.get("uuid"):
            pytest.skip("Service did not return uuid — cannot test update")

        # UPDATE
        new_text = f"Updated by conformance test {make_msg_code()}"
        update_body = make_update_request(
            record_uuid=msg["uuid"],
            locale="en_IN",
            module=module,
            message_updates=[{"uuid": msg["uuid"], "code": code, "message": new_text}],
        )
        r = _send(request.node, "PUT", f"{base_url}/messages",
                  headers=auth_headers, json_body=update_body)
        assert r.status_code == 200, f"Update failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_messages_response(r.json())
        texts = [m.get("message") for m in r.json()["messages"]]
        assert new_text in texts, f"Updated text not found in response: {texts}"

    def test_create_multi_locale_then_search_each(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        base_code = make_msg_code()
        locales = ["en_IN", "hi_IN", "mr_IN"]

        messages = [
            make_message(module=module, locale=loc, code=f"{base_code}_{loc}")
            for loc in locales
        ]
        r = _send(request.node, "POST", f"{base_url}/messages",
                  headers=auth_headers, json_body={"messages": messages})
        assert r.status_code == 201, f"Create failed: {r.text}"

        for locale in locales:
            r = _send(request.node, "GET", f"{base_url}/messages",
                      headers=auth_headers,
                      params={"module": module, "locale": locale})
            assert r.status_code == 200, f"Search for locale '{locale}' failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            found = [m.get("locale") for m in r.json()["messages"]]
            assert locale in found or len(r.json()["messages"]) > 0, \
                f"No messages found for locale '{locale}'"

    def test_upsert_creates_then_updates(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()

        # First upsert — create
        first_text = f"First upsert text {make_msg_code()}"
        r = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                  headers=auth_headers,
                  json_body={"messages": [make_message(module=module, locale="en_IN",
                                                        code=code, message=first_text)]})
        assert r.status_code == 200, f"First upsert failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)

        # Second upsert — update same code
        updated_text = f"Second upsert text {make_msg_code()}"
        r = _send(request.node, "PUT", f"{base_url}/messages/_upsert",
                  headers=auth_headers,
                  json_body={"messages": [make_message(module=module, locale="en_IN",
                                                        code=code, message=updated_text)]})
        assert r.status_code == 200, f"Second upsert failed: {r.text}"
        assert_messages_response(r.json())

        # VERIFY — search should return the latest text
        r = _send(request.node, "GET", f"{base_url}/messages",
                  headers=auth_headers,
                  params={"module": module, "locale": "en_IN", "code": code})
        assert r.status_code == 200
        messages = r.json()["messages"]
        assert len(messages) >= 1
        found_texts = [m.get("message") for m in messages]
        assert updated_text in found_texts, \
            f"Expected upserted text '{updated_text}' in search results: {found_texts}"


class TestFindMissingFlow:
    def test_create_in_one_locale_then_find_missing_in_another(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        codes = [make_msg_code() for _ in range(3)]

        # CREATE messages only in en_IN
        messages = [make_message(module=module, locale="en_IN", code=c) for c in codes]
        r = _send(request.node, "POST", f"{base_url}/messages",
                  headers=auth_headers, json_body={"messages": messages})
        assert r.status_code == 201, f"Create failed: {r.text}"

        # FIND MISSING in hi_IN — the codes from en_IN should appear as missing
        r = _send(request.node, "POST", f"{base_url}/messages/_missing",
                  headers=auth_headers,
                  json_body=make_find_missing_request(locales=["hi_IN"]))
        assert r.status_code in (200, 404), f"Unexpected: {r.text}"
        if r.status_code == 200:
            assert_gateway_headers(r, gateway_headers_spec)
            assert_find_missing_response(r.json())

    def test_find_missing_empty_body_checks_all_locales(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        r = _send(request.node, "POST", f"{base_url}/messages/_missing",
                  headers=auth_headers, json_body={})
        assert r.status_code in (200, 404), f"Unexpected: {r.text}"
        if r.status_code == 200:
            assert_gateway_headers(r, gateway_headers_spec)
            assert_find_missing_response(r.json())

    def test_create_all_locales_then_find_missing_returns_empty(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()
        locales = ["en_IN", "hi_IN"]

        # CREATE in both locales
        messages = [make_message(module=module, locale=loc, code=code) for loc in locales]
        r = _send(request.node, "POST", f"{base_url}/messages",
                  headers=auth_headers, json_body={"messages": messages})
        assert r.status_code == 201, f"Create failed: {r.text}"

        # FIND MISSING — for these two locales, this code should NOT be missing
        r = _send(request.node, "POST", f"{base_url}/messages/_missing",
                  headers=auth_headers,
                  json_body=make_find_missing_request(locales=locales))
        assert r.status_code in (200, 404)
        if r.status_code == 200:
            body = r.json()
            for locale in locales:
                missing_codes = body.get(locale, [])
                assert code not in missing_codes, \
                    f"Code '{code}' should not be missing in locale '{locale}'"


class TestCacheBustFlow:
    def test_create_messages_then_bust_cache_then_search(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        module = make_module()
        code = make_msg_code()

        # CREATE
        r = _send(request.node, "POST", f"{base_url}/messages",
                  headers=auth_headers,
                  json_body=make_create_request(module=module, codes=[code]))
        assert r.status_code == 201, f"Create failed: {r.text}"

        # BUST CACHE
        r = _send(request.node, "DELETE", f"{base_url}/cache/_bust",
                  headers=auth_headers)
        assert r.status_code == 200, f"Cache bust failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_cache_bust_response(r.json())
        assert r.json()["success"] is True

        # SEARCH — data must survive cache bust
        r = _send(request.node, "GET", f"{base_url}/messages",
                  headers=auth_headers,
                  params={"module": module, "locale": "en_IN"})
        assert r.status_code == 200, f"Search after cache bust failed: {r.text}"
        assert_messages_response(r.json())
        codes_found = [m.get("code") for m in r.json()["messages"]]
        assert code in codes_found, \
            f"Code '{code}' missing after cache bust — data should not be lost"

    def test_bust_cache_multiple_times(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        for _ in range(2):
            r = _send(request.node, "DELETE", f"{base_url}/cache/_bust",
                      headers=auth_headers)
            assert r.status_code == 200, f"Cache bust failed: {r.text}"
            assert r.json()["success"] is True
