import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_required_fields,
    assert_field_types,
    assert_enum_values,
    GENDER_VALUES,
)
from tests.helpers.factories import make_individual, make_config


def _send(node, method, url, headers=None, json_body=None):
    """Prepare, attach cURL for HTML report, then send."""
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── Individual search ─────────────────────────────────────────────────────────

class TestIndividualSearchContract:
    def test_search_returns_object_with_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/individuals", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["Individuals", "totalCount"])
        assert isinstance(body["Individuals"], list)
        assert isinstance(body["totalCount"], int)

    def test_search_individual_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/individuals", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json().get("Individuals", []):
            assert_required_fields(item, ["name", "dateOfBirth"])
            assert_field_types(item, {"id": str, "name": str, "dateOfBirth": str})
            assert_enum_values(item, {"gender": GENDER_VALUES})

    def test_search_with_name_filter(self, request, base_url, auth_headers, gateway_headers_spec):
        r = req_lib.Request("GET", f"{base_url}/individuals",
                            headers=auth_headers, params={"name": "Test"})
        prepared = r.prepare()
        attach_curl(request.node, prepared)
        response = req_lib.Session().send(prepared)

        assert response.status_code in (200, 404)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_pagination_params(self, request, base_url, auth_headers, gateway_headers_spec):
        r = req_lib.Request("GET", f"{base_url}/individuals",
                            headers=auth_headers, params={"limit": 10, "offset": 0})
        prepared = r.prepare()
        attach_curl(request.node, prepared)
        response = req_lib.Session().send(prepared)

        assert response.status_code == 200
        body = response.json()
        assert_required_fields(body, ["Individuals", "totalCount"])
        assert body["totalCount"] >= 0
        assert_gateway_headers(response, gateway_headers_spec)


# ── Individual create ─────────────────────────────────────────────────────────

class TestIndividualCreateContract:
    def test_create_returns_201_with_individual(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body=make_individual())

        assert response.status_code == 201
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["Individual"])
        ind = body["Individual"]
        assert_required_fields(ind, ["id", "name", "dateOfBirth"])
        assert_field_types(ind, {"id": str, "name": str, "dateOfBirth": str})

        req_lib.Session().send(
            req_lib.Request("DELETE", f"{base_url}/individuals/{ind['id']}",
                            headers=auth_headers).prepare()
        )

    def test_create_response_individual_has_id(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body=make_individual())

        assert response.status_code == 201
        ind = response.json()["Individual"]
        assert ind.get("id"), "id must be non-empty in create response"

        req_lib.Session().send(
            req_lib.Request("DELETE", f"{base_url}/individuals/{ind['id']}",
                            headers=auth_headers).prepare()
        )


# ── Individual get by ID ──────────────────────────────────────────────────────

class TestIndividualGetContract:
    def test_get_by_id_returns_individual_wrapper(self, request, base_url, auth_headers, gateway_headers_spec):
        create_r = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body=make_individual())
        assert create_r.status_code == 201
        individual_id = create_r.json()["Individual"]["id"]
        try:
            response = _send(request.node, "GET",
                             f"{base_url}/individuals/{individual_id}",
                             headers=auth_headers)

            assert response.status_code == 200
            assert_json_content_type(response)
            assert_gateway_headers(response, gateway_headers_spec)

            body = response.json()
            assert_required_fields(body, ["Individual"])
            assert body["Individual"]["id"] == individual_id
        finally:
            req_lib.Session().send(
                req_lib.Request("DELETE", f"{base_url}/individuals/{individual_id}",
                                headers=auth_headers).prepare()
            )

    def test_get_nonexistent_id_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        import uuid
        response = _send(request.node, "GET",
                         f"{base_url}/individuals/{uuid.uuid4()}",
                         headers=auth_headers)

        assert response.status_code == 404
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── Config upsert ─────────────────────────────────────────────────────────────

class TestConfigUpsertContract:
    def test_upsert_config_returns_config_object(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers, json_body=make_config())

        assert response.status_code in (200, 201)
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["key", "value"])
        assert_field_types(body, {"key": str, "value": str})

    def test_upsert_config_update_returns_200(self, request, base_url, auth_headers, gateway_headers_spec):
        cfg = make_config()
        _send(request.node, "POST", f"{base_url}/configs",
              headers=auth_headers, json_body=cfg)

        cfg["value"] = "updated-value"
        response = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers, json_body=cfg)

        assert response.status_code in (200, 201)
        assert_gateway_headers(response, gateway_headers_spec)
