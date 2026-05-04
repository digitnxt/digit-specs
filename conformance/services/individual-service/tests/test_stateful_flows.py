import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_individual,
    make_individual_update,
    make_individual_with_address,
    make_individual_with_document,
    make_config,
    _unique_mobile,
)
from tests.helpers.validators import assert_gateway_headers, assert_required_fields


def _send(node, method, url, headers=None, json_body=None):
    """Prepare, attach cURL (overrides previous on same node), then send."""
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(url, headers):
    """Best-effort cleanup — swallows errors."""
    try:
        req_lib.Session().send(
            req_lib.Request("DELETE", url, headers=headers).prepare()
        )
    except Exception:
        pass


class TestIndividualLifecycle:
    """Full CRUD lifecycle: create → get → search → update → delete."""

    def test_create_read_update_delete(self, request, base_url, auth_headers, gateway_headers_spec):
        individual_id = None
        try:
            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers,
                      json_body=make_individual(name="Lifecycle Test User"))
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            individual_id = r.json()["Individual"]["id"]
            assert individual_id

            # 2. GET by ID
            r = _send(request.node, "GET",
                      f"{base_url}/individuals/{individual_id}",
                      headers=auth_headers)
            assert r.status_code == 200, f"GET failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert r.json()["Individual"]["id"] == individual_id

            # 3. SEARCH by name
            req_r = req_lib.Request("GET", f"{base_url}/individuals",
                                    headers=auth_headers, params={"name": "Lifecycle"})
            prepared = req_r.prepare()
            attach_curl(request.node, prepared)
            r = req_lib.Session().send(prepared)
            assert r.status_code in (200, 404)
            if r.status_code == 200:
                ids = [i["id"] for i in r.json().get("Individuals", [])]
                assert individual_id in ids

            # 4. PUT (full update)
            r = _send(request.node, "PUT",
                      f"{base_url}/individuals/{individual_id}",
                      headers=auth_headers,
                      json_body=make_individual_update())
            assert r.status_code == 200, f"PUT failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert r.json()["Individual"]["name"] == "Updated Test User"

            # 5. SOFT-DELETE
            r = _send(request.node, "DELETE",
                      f"{base_url}/individuals/{individual_id}",
                      headers=auth_headers)
            assert r.status_code == 200, f"Delete failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)

            deleted_id = individual_id
            individual_id = None  # mark cleaned up

            # 6. CONFIRM DELETION → 404
            r = _send(request.node, "GET",
                      f"{base_url}/individuals/{deleted_id}",
                      headers=auth_headers)
            assert r.status_code == 404

        finally:
            if individual_id:
                _cleanup(f"{base_url}/individuals/{individual_id}", auth_headers)


class TestIndividualWithAddress:
    """Create individual with embedded address."""

    def test_create_with_address(self, request, base_url, auth_headers, gateway_headers_spec):
        individual_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers, json_body=make_individual_with_address())
            assert r.status_code == 201, f"Create with address failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            ind = r.json()["Individual"]
            individual_id = ind["id"]

            if "address" in ind:
                assert isinstance(ind["address"], dict), "address must be an object"
        finally:
            if individual_id:
                _cleanup(f"{base_url}/individuals/{individual_id}", auth_headers)


class TestIndividualWithDocument:
    """Create individual with attached document."""

    def test_create_with_document(self, request, base_url, auth_headers, gateway_headers_spec):
        individual_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers, json_body=make_individual_with_document())
            assert r.status_code == 201, f"Create with document failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            ind = r.json()["Individual"]
            individual_id = ind["id"]

            if "documents" in ind:
                assert isinstance(ind["documents"], list)
                for doc in ind["documents"]:
                    assert "documentType" in doc
                    assert "fileStoreId" in doc
        finally:
            if individual_id:
                _cleanup(f"{base_url}/individuals/{individual_id}", auth_headers)


class TestIndividualSearchPagination:
    """Verify pagination fields on search response."""

    def test_totalcount_is_non_negative(self, request, base_url, auth_headers, gateway_headers_spec):
        r = req_lib.Request("GET", f"{base_url}/individuals",
                            headers=auth_headers, params={"limit": 5, "offset": 0})
        prepared = r.prepare()
        attach_curl(request.node, prepared)
        response = req_lib.Session().send(prepared)

        assert response.status_code == 200
        assert response.json()["totalCount"] >= 0
        assert_gateway_headers(response, gateway_headers_spec)

    def test_search_by_mobile_number(self, request, base_url, auth_headers, gateway_headers_spec):
        mobile = _unique_mobile()
        individual_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers,
                      json_body=make_individual(mobileNumber=mobile))
            assert r.status_code == 201
            individual_id = r.json()["Individual"]["id"]

            req_r = req_lib.Request("GET", f"{base_url}/individuals",
                                    headers=auth_headers, params={"mobileNumber": mobile})
            prepared = req_r.prepare()
            attach_curl(request.node, prepared)
            r = req_lib.Session().send(prepared)

            assert r.status_code in (200, 404)
            if r.status_code == 200:
                ids = [i["id"] for i in r.json().get("Individuals", [])]
                assert individual_id in ids
        finally:
            if individual_id:
                _cleanup(f"{base_url}/individuals/{individual_id}", auth_headers)


class TestConfigUpsertFlow:
    """Upsert config: create then update same key."""

    def test_create_then_update_config(self, request, base_url, auth_headers, gateway_headers_spec):
        cfg = make_config()

        r = _send(request.node, "POST", f"{base_url}/configs",
                  headers=auth_headers, json_body=cfg)
        assert r.status_code in (200, 201), f"Config create failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert r.json()["key"] == cfg["key"]

        cfg["value"] = "updated-conformance-value"
        r = _send(request.node, "POST", f"{base_url}/configs",
                  headers=auth_headers, json_body=cfg)
        assert r.status_code in (200, 201), f"Config update failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert r.json()["value"] == "updated-conformance-value"

    def test_multiple_config_keys(self, request, base_url, auth_headers, gateway_headers_spec):
        for i in range(3):
            cfg = make_config(key=f"conf.batch.{i}", value=f"value-{i}")
            r = _send(request.node, "POST", f"{base_url}/configs",
                      headers=auth_headers, json_body=cfg)
            assert r.status_code in (200, 201), f"Batch config {i} failed: {r.text}"
            assert r.json()["key"] == cfg["key"]
