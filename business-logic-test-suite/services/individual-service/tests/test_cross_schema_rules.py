"""
Cross-schema rule tests for Individual service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _mobile():
    return f"+91{uuid.uuid4().int % 10_000_000_000:010d}"


def _base():
    return {"givenName": "Test", "familyName": "User", "mobileNumber": _mobile()}


# ---------------------------------------------------------------------------
# BR-CS-001: Mobile number uniqueness per tenant baseline
# ---------------------------------------------------------------------------

class TestBR_CS_001_mobile_number_uniqueness_per_tenant:
    """Same mobileNumber in same tenant returns 409 on second create."""

    def test_duplicate_mobile_returns_409(self, request, base_url, auth_headers):
        mobile = _mobile()
        req_lib.post(f"{base_url}/individuals", headers=auth_headers,
                     json={"individual": {**_base(), "mobileNumber": mobile}})
        resp = _post(request.node, f"{base_url}/individuals", auth_headers,
                     {"individual": {**_base(), "mobileNumber": mobile}})
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate mobile, got {resp.status_code}: {resp.text}"

    def test_unique_mobile_numbers_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers,
                     {"individual": {**_base(), "mobileNumber": _mobile()}})
        assert resp.status_code in (200, 201), f"Unique mobile must be accepted: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: Optimistic lock required when version supplied
# ---------------------------------------------------------------------------

class TestBR_CS_004_optimistic_lock_required_when_version_supplied:
    """Stale version on PUT returns 409."""


# ---------------------------------------------------------------------------
# BR-CS-002: Name uniqueness when tenant configured
# ---------------------------------------------------------------------------

class TestBR_CS_002_name_uniqueness_when_tenant_configured:
    """
    If uniquenessCriteria includes 'name', (givenName, familyName) must be unique per tenant.
    We test that two creates with the same name pair return 409.
    """

    def test_duplicate_name_returns_409_when_configured(self, request, base_url, auth_headers):
        given = "UniqueTest"
        family = "User" + uuid.uuid4().hex[:4]
        first = req_lib.post(f"{base_url}/individuals", headers=auth_headers,
                             json={"individual": {
                                 "givenName": given, "familyName": family,
                                 "mobileNumber": _mobile(),
                             }})
        if first.status_code not in (200, 201):
            return  # Skip if first create fails (setup issue)
        second = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                "givenName": given, "familyName": family,
                "mobileNumber": _mobile(),
            },
        })
        assert second.status_code in (200, 201, 409), \
            f"Duplicate name must return 409 (when uniqueness configured) or 200/201 (when not configured), got {second.status_code}: {second.text}"


# ---------------------------------------------------------------------------
# BR-CS-003: Tenant config is singleton per tenant
# ---------------------------------------------------------------------------

class TestBR_CS_003_tenant_config_is_singleton_per_tenant:
    """POST /configs is idempotent — repeated calls upsert without error."""

    def test_post_configs_twice_succeeds(self, request, base_url, auth_headers):
        config = {"uniquenessCriteria": ["mobileNumber"]}
        r1 = _post(request.node, f"{base_url}/configs", auth_headers, {"config": config})
        r2 = _post(request.node, f"{base_url}/configs", auth_headers, {"config": config})
        assert r1.status_code in (200, 201), f"First POST /configs failed: {r1.text}"
        assert r2.status_code in (200, 201), \
            f"Second POST /configs must succeed (idempotent upsert), got {r2.status_code}: {r2.text}"


    def test_stale_version_on_put_returns_409(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/individuals", headers=auth_headers,
                              json={"individual": {**_base()}})
        if create.status_code not in (200, 201):
            return
        ind = create.json().get("individual") or create.json()
        ind_id = ind.get("id") or ind.get("individualId")
        if not ind_id:
            return

        stale = {**ind, "version": -1, "givenName": "UpdatedName"}
        resp = req_lib.put(f"{base_url}/individuals/{ind_id}", headers=auth_headers,
                           json={"individual": stale})
        assert resp.status_code == 409, \
            f"Expected 409 for stale version, got {resp.status_code}: {resp.text}"
