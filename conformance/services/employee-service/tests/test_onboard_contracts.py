"""
Contracts for POST /employees/onboard and the `userIds` search filter (added in 401e8e3).

Onboard creates a Keycloak user, an individual and an employee in one call. Cleanup is
best-effort in reverse order: hard-delete the employee, soft-delete the individual, then
delete the Keycloak user — all scoped to the tenant under test (realm == tenant).
"""
import uuid

import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_required_fields,
    assert_bare_array,
    assert_error_array,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _mobile():
    return "9" + str(uuid.uuid4().int)[:9]


def _letters():
    return "".join(c for c in uuid.uuid4().hex if c.isalpha())[:6].ljust(6, "x")


def make_onboard(**overrides):
    mobile = _mobile()
    body = {
        "user": {"mobileNumber": mobile, "password": "Conf@12345",
                 "firstName": "Conformance", "lastName": _letters()},
        "individual": {"givenName": f"Conformance {_letters()}", "gender": "OTHER",
                       "mobileNumber": mobile},
        "employee": {"employeeType": "PERMANENT", "department": "CONF-DEPT",
                     "designation": "CONF-DESIG", "isActive": True, "jurisdictions": []},
    }
    body.update(overrides)
    return body


def _host(base_url):
    return base_url.split("/employee/")[0]


def _cleanup_onboard(base_url, headers, tenant, body):
    emp, ind, user = body.get("employee") or {}, body.get("individual") or {}, body.get("user") or {}
    host = _host(base_url)
    for url in (
        emp.get("id") and f"{base_url}/employees/{emp['id']}",
        ind.get("id") and f"{host}/individuals/v3/individuals/{ind['id']}",
        user.get("id") and tenant and f"{host}/keycloak/admin/realms/{tenant}/users/{user['id']}",
    ):
        if url:
            try:
                req_lib.delete(url, headers=headers, timeout=30)
            except Exception:
                pass


@pytest.fixture(scope="session")
def tenant(request):
    return request.config.getoption("--tenant-id")


class TestOnboardContract:
    def test_onboard_returns_201_linked_records(self, request, base_url, auth_headers,
                                                gateway_headers_spec, tenant):
        payload = make_onboard()
        r = _send(request.node, "POST", f"{base_url}/employees/onboard",
                  headers=auth_headers, json_body=payload)
        body = r.json() if r.headers.get("Content-Type", "").startswith("application/json") else {}
        try:
            assert r.status_code == 201, f"got {r.status_code}: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_required_fields(body, ["user", "individual", "employee"])
            assert_required_fields(body["user"], ["id", "username"])
            assert body["user"]["username"] == payload["user"]["mobileNumber"]
            assert "password" not in body["user"], "password must never be returned"
            assert body["employee"]["userId"] == body["user"]["id"]
            assert body["employee"]["individualId"] == body["individual"]["id"]
            assert body["employee"]["version"] == 1
        finally:
            if isinstance(body, dict):
                _cleanup_onboard(base_url, auth_headers, tenant, body)

    @pytest.mark.parametrize("drop", ["user", "individual", "employee"])
    def test_missing_block_returns_400(self, request, base_url, auth_headers, drop):
        payload = make_onboard()
        payload.pop(drop)
        r = _send(request.node, "POST", f"{base_url}/employees/onboard",
                  headers=auth_headers, json_body=payload)
        assert r.status_code == 400, f"got {r.status_code}: {r.text}"
        assert_error_array(r.json())

    @pytest.mark.parametrize("field", ["mobileNumber", "password"])
    def test_missing_user_field_returns_400(self, request, base_url, auth_headers, field):
        payload = make_onboard()
        payload["user"].pop(field)
        r = _send(request.node, "POST", f"{base_url}/employees/onboard",
                  headers=auth_headers, json_body=payload)
        assert r.status_code == 400, f"got {r.status_code}: {r.text}"
        assert_error_array(r.json())

    def test_unknown_role_returns_400(self, request, base_url, auth_headers):
        payload = make_onboard()
        payload["user"]["roles"] = [f"NO_SUCH_ROLE_{uuid.uuid4().hex[:6].upper()}"]
        r = _send(request.node, "POST", f"{base_url}/employees/onboard",
                  headers=auth_headers, json_body=payload)
        assert r.status_code == 400, f"got {r.status_code}: {r.text}"
        assert_error_array(r.json())

    def test_missing_auth_returns_401(self, request, base_url):
        r = _send(request.node, "POST", f"{base_url}/employees/onboard", json_body=make_onboard())
        assert r.status_code == 401, f"got {r.status_code}: {r.text}"


class TestUserIdsFilter:
    def test_search_by_user_ids(self, request, base_url, auth_headers, tenant):
        r = _send(request.node, "POST", f"{base_url}/employees/onboard",
                  headers=auth_headers, json_body=make_onboard())
        body = r.json() if r.status_code == 201 else {}
        try:
            if r.status_code != 201:
                pytest.skip(f"onboard unavailable ({r.status_code}), cannot seed a userId")
            uid = body["user"]["id"]
            r = _send(request.node, "GET", f"{base_url}/employees",
                      headers=auth_headers, params={"userIds": [uid, "no-such-user"]})
            assert r.status_code == 200, f"got {r.status_code}: {r.text}"
            items = assert_bare_array(r.json())
            assert [e["id"] for e in items] == [body["employee"]["id"]]
        finally:
            _cleanup_onboard(base_url, auth_headers, tenant, body)

    def test_unknown_user_id_returns_empty(self, request, base_url, auth_headers):
        r = _send(request.node, "GET", f"{base_url}/employees", headers=auth_headers,
                  params={"userIds": f"no-such-{uuid.uuid4().hex[:8]}"})
        assert r.status_code == 200
        assert assert_bare_array(r.json()) == []

    def test_blank_user_id_returns_400(self, request, base_url, auth_headers):
        # A blank entry in the list is a 400. A lone `?userIds=` binds to an empty list and
        # is treated as "no filter" (platform-wide behaviour for empty list params).
        r = _send(request.node, "GET", f"{base_url}/employees", headers=auth_headers,
                  params={"userIds": ["abc", " "]})
        assert r.status_code == 400, f"got {r.status_code}: {r.text}"
        assert_error_array(r.json())
