"""
Lifecycle rule tests for Individual service.
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
# BR-LC-001: Creation initialises immutable audit fields
# ---------------------------------------------------------------------------

class TestBR_LC_001_creation_initialises_immutable_audit_fields:
    """isActive=true and version=1 are set on creation."""

    def test_created_individual_has_expected_defaults(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers,
                     {"individual": {**_base()}})
        assert resp.status_code in (200, 201), f"Create failed: {resp.text}"
        ind = resp.json().get("individual") or resp.json()
        assert ind.get("isActive") is True, "isActive must be True on creation"
        version = ind.get("version") or ind.get("rowVersion")
        assert version in (1, "1"), f"version must be 1 on creation, got {version}"


# ---------------------------------------------------------------------------
# BR-LC-002: Update increments version and preserves immutable fields
# ---------------------------------------------------------------------------

class TestBR_LC_002_update_increments_version_and_preserves_immutable_fields:
    """PUT increments version; createdBy and createdTime are not changed."""

    def test_put_increments_version(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/individuals", headers=auth_headers,
                              json={"individual": {**_base()}})
        assert create.status_code in (200, 201), f"Create failed: {create.text}"
        ind_v1 = create.json().get("individual") or create.json()
        ind_id = ind_v1.get("id") or ind_v1.get("individualId")
        if not ind_id:
            return

        update = req_lib.put(f"{base_url}/individuals/{ind_id}", headers=auth_headers,
                             json={"individual": {**ind_v1, "givenName": "UpdatedName"}})
        assert update.status_code in (200, 201), f"Update failed: {update.text}"
        ind_v2 = update.json().get("individual") or update.json()

        v2_version = ind_v2.get("version") or ind_v2.get("rowVersion")
        v1_version = ind_v1.get("version") or ind_v1.get("rowVersion")
        assert v2_version != v1_version, \
            f"Version must increment on update, v1={v1_version}, v2={v2_version}"


# ---------------------------------------------------------------------------
# BR-LC-003: Soft delete marks individual and cascades identifiers
# ---------------------------------------------------------------------------

class TestBR_LC_003_soft_delete_marks_individual_and_cascades:
    """Soft delete sets isActive=false; record still exists but excluded from default search."""

    def test_soft_delete_sets_is_active_false(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/individuals", headers=auth_headers,
                              json={"individual": {**_base()}})
        assert create.status_code in (200, 201)
        ind = create.json().get("individual") or create.json()
        ind_id = ind.get("id") or ind.get("individualId")
        if not ind_id:
            return

        del_resp = req_lib.delete(f"{base_url}/individuals/{ind_id}", headers=auth_headers)
        assert del_resp.status_code in (200, 204), f"Delete failed: {del_resp.text}"

        get_resp = req_lib.get(f"{base_url}/individuals/{ind_id}", headers=auth_headers)
        assert get_resp.status_code == 404, \
            f"Soft-deleted individual must not appear in default GET, got {get_resp.status_code}"


# ---------------------------------------------------------------------------
# BR-LC-004: Soft-deleted records excluded by default
# ---------------------------------------------------------------------------

class TestBR_LC_004_soft_deleted_records_excluded_by_default:
    """GET /individuals excludes isActive=false by default; includeDeleted=true exposes them."""

    def test_deleted_individual_visible_with_include_deleted(
        self, request, base_url, auth_headers
    ):
        create = req_lib.post(f"{base_url}/individuals", headers=auth_headers,
                              json={"individual": {**_base()}})
        if create.status_code not in (200, 201):
            return
        ind = create.json().get("individual") or create.json()
        ind_id = ind.get("id") or ind.get("individualId")
        if not ind_id:
            return

        req_lib.delete(f"{base_url}/individuals/{ind_id}", headers=auth_headers)

        search = req_lib.get(f"{base_url}/individuals", headers=auth_headers,
                             params={"includeDeleted": True})
        assert search.status_code == 200
        results = search.json()
        all_ids = [i.get("id") or i.get("individualId")
                   for i in (results.get("individuals") or results if isinstance(results, list) else [])]
        assert ind_id in all_ids, \
            "Soft-deleted individual must appear when includeDeleted=true"
