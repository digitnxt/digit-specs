"""
Stateful / lifecycle tests for the Individual Service.

These tests chain multiple operations against the live service and verify
behaviour across calls:

- Full CRUD lifecycle (create → get → search → update → soft-delete → verify 404).
- Existence check before/after create and after soft-delete.
- Config upsert flow: first call may be 201 (or 200 if a config already
  exists for the tenant); subsequent calls are 200.
- Search by mobileNumber round-trip.
"""

import requests as req_lib

from tests.helpers.curl_builder import attach_curl
from tests.helpers.factories import (
    make_individual,
    make_individual_with_address,
    make_individual_with_identifiers,
    make_individual_with_documents,
    make_individual_update,
    make_config_request,
    _unique_mobile,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_individual_shape,
    assert_individual_search_response,
    assert_exists_response,
    assert_config_response,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(url, headers):
    try:
        req_lib.Session().send(
            req_lib.Request("DELETE", url, headers=headers).prepare()
        )
    except Exception:
        pass


# ── Full lifecycle ────────────────────────────────────────────────────────────

class TestIndividualLifecycle:
    """CREATE → GET → SEARCH → UPDATE → DELETE → verify 404."""

    def test_create_read_update_delete(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        ind_id = None
        try:
            # 1. CREATE
            body = make_individual()
            create_r = _send(request.node, "POST", f"{base_url}/individuals",
                             headers=auth_headers, json_body=body)
            assert create_r.status_code == 201, f"create failed: {create_r.text}"
            assert_gateway_headers(create_r, gateway_headers_spec)
            ind = create_r.json()
            assert_individual_shape(ind)
            ind_id = ind["id"]
            current_version = ind.get("version", 1)
            assert ind["givenName"] == body["givenName"]

            # 2. GET by id
            get_r = _send(request.node, "GET",
                          f"{base_url}/individuals/{ind_id}",
                          headers=auth_headers)
            assert get_r.status_code == 200, f"get failed: {get_r.text}"
            assert_gateway_headers(get_r, gateway_headers_spec)
            assert get_r.json()["id"] == ind_id

            # 3. SEARCH by givenName — created record must appear
            search_r = _send(request.node, "GET", f"{base_url}/individuals",
                             headers=auth_headers,
                             params={"givenName": body["givenName"]})
            assert search_r.status_code == 200, f"search failed: {search_r.text}"
            search_body = search_r.json()
            assert_individual_search_response(search_body)
            ids = [i["id"] for i in search_body["individuals"]]
            assert ind_id in ids, \
                f"created id {ind_id} not found via givenName search"

            # 4. UPDATE — full replace. Pass current version so optimistic
            # locking accepts the write.
            update_body = make_individual_update(
                givenName="Lifecycle Updated",
                version=current_version,
            )
            put_r = _send(request.node, "PUT",
                          f"{base_url}/individuals/{ind_id}",
                          headers=auth_headers, json_body=update_body)
            assert put_r.status_code == 200, f"update failed: {put_r.text}"
            assert_gateway_headers(put_r, gateway_headers_spec)
            updated = put_r.json()
            assert_individual_shape(updated)
            assert updated["id"] == ind_id, "update must preserve id"
            assert updated["givenName"] == "Lifecycle Updated"

            # 5. SOFT-DELETE — returns 204 No Content per spec.
            del_r = _send(request.node, "DELETE",
                          f"{base_url}/individuals/{ind_id}",
                          headers=auth_headers)
            assert del_r.status_code == 204, f"delete failed: {del_r.status_code} {del_r.text}"
            deleted_id = ind_id
            ind_id = None  # mark as cleaned

            # 6. VERIFY DELETION: GET returns 404 (soft-deleted records are excluded)
            after_r = _send(request.node, "GET",
                            f"{base_url}/individuals/{deleted_id}",
                            headers=auth_headers)
            assert after_r.status_code == 404, \
                f"GET on soft-deleted record must be 404, got {after_r.status_code}"
        finally:
            if ind_id:
                _cleanup(f"{base_url}/individuals/{ind_id}", auth_headers)


class TestIndividualWithNestedEntities:
    """Create individuals with embedded address / identifiers / documents and
    verify round-trip."""

    def test_create_with_address_round_trips(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        ind_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers, json_body=make_individual_with_address())
            assert r.status_code == 201, f"create with address failed: {r.text}"
            ind = r.json()
            ind_id = ind["id"]
            assert isinstance(ind.get("address"), list) and len(ind["address"]) >= 1, \
                "address[] must round-trip"
            assert ind["address"][0]["city"] == "Bengaluru"
        finally:
            if ind_id:
                _cleanup(f"{base_url}/individuals/{ind_id}", auth_headers)

    def test_create_with_identifiers_round_trips(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        ind_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers, json_body=make_individual_with_identifiers())
            assert r.status_code == 201, f"create with identifiers failed: {r.text}"
            ind = r.json()
            ind_id = ind["id"]
            assert isinstance(ind.get("identifiers"), list) and len(ind["identifiers"]) >= 1, \
                "identifiers[] must round-trip"
            assert ind["identifiers"][0]["identifierType"] == "AADHAAR"
        finally:
            if ind_id:
                _cleanup(f"{base_url}/individuals/{ind_id}", auth_headers)

    def test_create_with_documents_round_trips(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        ind_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers, json_body=make_individual_with_documents())
            assert r.status_code == 201, f"create with documents failed: {r.text}"
            ind = r.json()
            ind_id = ind["id"]
            assert isinstance(ind.get("documents"), list) and len(ind["documents"]) >= 1, \
                "documents[] must round-trip"
            assert ind["documents"][0]["documentType"] == "PROOF_OF_RESIDENCE"
        finally:
            if ind_id:
                _cleanup(f"{base_url}/individuals/{ind_id}", auth_headers)


# ── /individuals/exists end-to-end ────────────────────────────────────────────

class TestExistsLifecycle:
    """exists=true after create, exists=false after soft-delete."""

    def test_exists_true_after_create_false_after_delete(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        create_r = _send(request.node, "POST", f"{base_url}/individuals",
                         headers=auth_headers, json_body=make_individual())
        assert create_r.status_code == 201, f"setup create failed: {create_r.text}"
        ind_id = create_r.json()["id"]

        # exists=true
        exists_r = _send(request.node, "GET", f"{base_url}/individuals/exists",
                         headers=auth_headers, params={"id": ind_id})
        assert exists_r.status_code == 200
        assert_exists_response(exists_r.json())
        assert exists_r.json()["exists"] is True

        # soft-delete — 204 No Content per spec
        del_r = _send(request.node, "DELETE", f"{base_url}/individuals/{ind_id}",
                      headers=auth_headers)
        assert del_r.status_code == 204, \
            f"delete should return 204, got {del_r.status_code}: {del_r.text}"

        # exists=false (soft-deleted records are excluded unless includeDeleted=true)
        after_r = _send(request.node, "GET", f"{base_url}/individuals/exists",
                        headers=auth_headers, params={"id": ind_id})
        assert after_r.status_code == 200
        assert_exists_response(after_r.json())
        assert after_r.json()["exists"] is False, \
            "soft-deleted record should be excluded from default exists check"


# ── Search round-trip ────────────────────────────────────────────────────────

class TestSearchRoundTrip:
    """Verify a created record is findable via different filter combinations."""

    def test_search_by_mobile_number(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        mobile = _unique_mobile()
        ind_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/individuals",
                      headers=auth_headers,
                      json_body=make_individual(mobileNumber=mobile))
            assert r.status_code == 201, f"create failed: {r.text}"
            ind_id = r.json()["id"]

            search_r = _send(request.node, "GET", f"{base_url}/individuals",
                             headers=auth_headers,
                             params={"mobileNumber": mobile})
            assert search_r.status_code == 200, f"search failed: {search_r.text}"
            assert_individual_search_response(search_r.json())
            ids = [i["id"] for i in search_r.json()["individuals"]]
            assert ind_id in ids, \
                f"created id {ind_id} not found via mobileNumber={mobile} search"
        finally:
            if ind_id:
                _cleanup(f"{base_url}/individuals/{ind_id}", auth_headers)

    def test_search_pagination_bounds(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        """page is 1-indexed; size is bounded 1..100."""
        # Default
        r = _send(request.node, "GET", f"{base_url}/individuals",
                  headers=auth_headers)
        assert r.status_code == 200
        body = r.json()
        assert body["page"] == 1
        # Explicit size
        r2 = _send(request.node, "GET", f"{base_url}/individuals",
                   headers=auth_headers, params={"page": 1, "size": 3})
        assert r2.status_code == 200
        assert r2.json()["size"] == 3
        assert len(r2.json()["individuals"]) <= 3


# ── Config upsert flow ────────────────────────────────────────────────────────

class TestConfigUpsertFlow:
    """upsert is 200 or 201 (201 first time per tenant; the test cannot
    reliably distinguish on shared environments). Then GET returns the
    stored config."""

    def test_upsert_then_get_returns_same_config(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        cfg = make_config_request(
            mobileRegex=r"^[6-9][0-9]{9}$",
            uniquenessCriteria=["mobileNumber"],
        )
        upsert_r = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers, json_body=cfg)
        assert upsert_r.status_code in (200, 201), f"upsert failed: {upsert_r.text}"
        assert_config_response(upsert_r.json())

        get_r = _send(request.node, "GET", f"{base_url}/configs",
                      headers=auth_headers)
        assert get_r.status_code == 200, f"get config failed: {get_r.text}"
        body = get_r.json()
        assert_config_response(body)
        assert body.get("mobileRegex") == cfg["mobileRegex"], \
            f"GET should return the upserted mobileRegex; got {body.get('mobileRegex')!r}"
        assert "mobileNumber" in (body.get("uniquenessCriteria") or []), \
            "GET should reflect uniquenessCriteria from upsert"

    def test_second_upsert_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        """Per spec: 201 first time per tenant, 200 on subsequent updates.
        The first call here MAY be 200 if a config already exists. The second
        call MUST be 200."""
        _send(request.node, "POST", f"{base_url}/configs",
              headers=auth_headers, json_body=make_config_request())
        second_r = _send(request.node, "POST", f"{base_url}/configs",
                         headers=auth_headers,
                         json_body=make_config_request(uniquenessCriteria=["name"]))
        assert second_r.status_code == 200, \
            f"second upsert must return 200, got {second_r.status_code}: {second_r.text}"
