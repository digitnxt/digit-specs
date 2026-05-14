import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_template,
    make_template_dated,
    make_template_update,
    make_template_with_variable,
    make_template_with_rand,
    make_generate_request,
    make_bulk_generate_request,
    _tpl_code,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_service_response_headers,
    assert_json_content_type,
    assert_template_response_shape,
    assert_generate_response_shape,
    assert_bulk_generate_response_shape,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _delete_template(base_url, code, version, headers):
    try:
        req_lib.delete(f"{base_url}/template",
                       params={"templateCode": code, "version": version},
                       headers=headers)
    except Exception:
        pass


# ── Template Create ───────────────────────────────────────────────────────────

class TestTemplateCreateContract:
    def test_create_returns_201_with_correct_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        payload = make_template(code=code)
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=payload)
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_template_response_shape(body)
            assert body["templateCode"] == code
            assert body["version"] == "v1"
            assert body["config"]["template"] == payload["config"]["template"]
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_create_with_dated_template(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        payload = make_template_dated(code=code)
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=payload)
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            body = r.json()
            assert_template_response_shape(body)
            assert body["config"]["template"] == payload["config"]["template"]
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_create_with_variable_template(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=make_template_with_variable(code=code))
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            assert_template_response_shape(r.json())
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_create_with_rand_template(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        r = _send(request.node, "POST", f"{base_url}/template",
                  headers=auth_headers, json_body=make_template_with_rand(code=code))
        try:
            assert r.status_code == 201, f"Expected 201, got {r.status_code}: {r.text}"
            assert_template_response_shape(r.json())
        finally:
            _delete_template(base_url, code, "v1", auth_headers)


# ── Template Update ───────────────────────────────────────────────────────────

class TestTemplateUpdateContract:
    def test_update_creates_new_version(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        r = _send(request.node, "PUT", f"{base_url}/template",
                  headers=auth_headers, json_body=make_template_update(code=code))
        try:
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_template_response_shape(body)
            assert body["version"] == "v2"
            assert body["templateCode"] == code
        finally:
            _delete_template(base_url, code, "v2", auth_headers)
            _delete_template(base_url, code, "v1", auth_headers)


# ── Template Search ───────────────────────────────────────────────────────────

class TestTemplateSearchContract:
    def test_search_returns_200_array(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "GET", f"{base_url}/template", headers=auth_headers)
        assert r.status_code == 200
        assert_json_content_type(r)
        assert_service_response_headers(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert isinstance(r.json(), list), f"Expected array, got: {type(r.json())}"

    def test_search_by_template_code_finds_created(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code})
            assert r.status_code == 200
            body = r.json()
            assert isinstance(body, list)
            assert any(t["templateCode"] == code for t in body), (
                f"Created template '{code}' not found in search results"
            )
            assert_gateway_headers(r, gateway_headers_spec)
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_search_by_template_code_and_version(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code, "version": "v1"})
            assert r.status_code == 200
            body = r.json()
            assert isinstance(body, list)
            if body:
                assert body[0]["version"] == "v1"
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_search_pagination_respects_limit(self, request, base_url, auth_headers, gateway_headers_spec):
        r = _send(request.node, "GET", f"{base_url}/template",
                  headers=auth_headers, params={"limit": 5, "offset": 0})
        assert r.status_code == 200
        body = r.json()
        assert isinstance(body, list)
        assert len(body) <= 5

    def test_search_each_result_has_correct_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "GET", f"{base_url}/template",
                      headers=auth_headers, params={"templateCode": code})
            assert r.status_code == 200
            for item in r.json():
                assert_template_response_shape(item)
        finally:
            _delete_template(base_url, code, "v1", auth_headers)


# ── Template Delete ───────────────────────────────────────────────────────────

class TestTemplateDeleteContract:
    def test_delete_returns_200_with_deleted_true(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        r = _send(request.node, "DELETE", f"{base_url}/template",
                  headers=auth_headers, params={"templateCode": code, "version": "v1"})
        assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
        assert_json_content_type(r)
        assert_service_response_headers(r)
        assert_gateway_headers(r, gateway_headers_spec)
        assert r.json().get("deleted") is True

    def test_delete_removes_template_from_search(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        _send(request.node, "DELETE", f"{base_url}/template",
              headers=auth_headers, params={"templateCode": code, "version": "v1"})
        r = _send(request.node, "GET", f"{base_url}/template",
                  headers=auth_headers, params={"templateCode": code})
        assert r.status_code == 200
        assert not any(t["templateCode"] == code for t in r.json()), (
            f"Deleted template '{code}' still visible in search results"
        )


# ── Generate ID ───────────────────────────────────────────────────────────────

class TestGenerateIDContract:
    def test_generate_returns_200_with_correct_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_generate_response_shape(body)
            assert body["templateCode"] == code
            assert body["version"] == "v1"
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_generate_with_variable_substitution(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template_with_variable(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers,
                      json_body=make_generate_request(code, variables={"ORG": "TESTORG"}))
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            body = r.json()
            assert_generate_response_shape(body)
            assert "TESTORG" in body["id"], (
                f"Variable substitution failed — 'TESTORG' not in generated ID: {body['id']}"
            )
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_consecutive_ids_are_unique(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r1 = _send(request.node, "POST", f"{base_url}/generate",
                       headers=auth_headers, json_body=make_generate_request(code))
            r2 = _send(request.node, "POST", f"{base_url}/generate",
                       headers=auth_headers, json_body=make_generate_request(code))
            assert r1.status_code == 200 and r2.status_code == 200
            assert r1.json()["id"] != r2.json()["id"], "Consecutive generated IDs must be unique"
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_generate_returns_latest_version(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        req_lib.put(f"{base_url}/template",
                    json=make_template_update(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate",
                      headers=auth_headers, json_body=make_generate_request(code))
            assert r.status_code == 200
            assert r.json()["version"] == "v2", (
                f"Expected latest version v2, got: {r.json().get('version')}"
            )
        finally:
            _delete_template(base_url, code, "v2", auth_headers)
            _delete_template(base_url, code, "v1", auth_headers)


# ── Bulk Generate IDs ─────────────────────────────────────────────────────────

class TestBulkGenerateIDContract:
    def test_bulk_generate_returns_correct_count(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            count = 10
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=count))
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            assert_json_content_type(r)
            assert_service_response_headers(r)
            assert_gateway_headers(r, gateway_headers_spec)
            body = r.json()
            assert_bulk_generate_response_shape(body, count)
            assert body["templateCode"] == code
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_bulk_ids_are_all_unique(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            count = 20
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=count))
            assert r.status_code == 200
            ids = r.json()["ids"]
            assert len(set(ids)) == count, f"Duplicate IDs found in bulk response: {ids}"
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_bulk_generate_with_variable_substitution(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template_with_variable(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=5,
                                                           variables={"ORG": "BLKORG"}))
            assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
            body = r.json()
            assert_bulk_generate_response_shape(body, 5)
            for id_val in body["ids"]:
                assert "BLKORG" in id_val, f"Variable not substituted in bulk ID: {id_val}"
        finally:
            _delete_template(base_url, code, "v1", auth_headers)

    def test_bulk_count_one_works(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template",
                     json=make_template(code=code), headers=auth_headers)
        try:
            r = _send(request.node, "POST", f"{base_url}/generate/bulk",
                      headers=auth_headers,
                      json_body=make_bulk_generate_request(code, count=1))
            assert r.status_code == 200
            body = r.json()
            assert_bulk_generate_response_shape(body, 1)
        finally:
            _delete_template(base_url, code, "v1", auth_headers)
