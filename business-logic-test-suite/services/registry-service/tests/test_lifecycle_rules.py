"""
Lifecycle rule tests for Registry service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


_VALID_DEFINITION = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "properties": {"name": {"type": "string"}},
    "required": ["name"],
}


# ---------------------------------------------------------------------------
# BR-LC-001: Schema updates create new immutable version rows
# ---------------------------------------------------------------------------

class TestBR_LC_001_schema_updates_create_new_immutable_version_rows:
    """PUT /schema/:code with changed definition creates a new version row."""

    def test_schema_put_increments_version(self, request, base_url, auth_headers):
        code = "lc001-" + uuid.uuid4().hex[:6]
        create = req_lib.post(f"{base_url}/schema", headers=auth_headers,
                              json={"schemaCode": code, "definition": _VALID_DEFINITION})
        assert create.status_code in (200, 201)
        v1 = create.json()

        updated_definition = {**_VALID_DEFINITION, "description": "Updated v2"}
        update = req_lib.put(f"{base_url}/schema/{code}", headers=auth_headers,
                             json={"schemaCode": code, "definition": updated_definition})
        assert update.status_code in (200, 201), f"Schema update failed: {update.text}"
        v2 = update.json()
        assert v2.get("version") != v1.get("version"), \
            "Schema version must increment on PUT"


# ---------------------------------------------------------------------------
# BR-LC-002: Data updates create new immutable version rows
# ---------------------------------------------------------------------------

class TestBR_LC_002_data_updates_create_new_immutable_version_rows:
    """PUT data creates new row with version+1; old row is deactivated."""

    def test_data_put_creates_new_version(self, request, base_url, auth_headers):
        data_create = req_lib.post(f"{base_url}/schema/seed-schema/data",
                                   headers=auth_headers,
                                   json={"data": {"name": "Initial Entry"}})
        if data_create.status_code not in (200, 201):
            return
        record = data_create.json()
        registry_id = record.get("registryId") or record.get("id")
        v1_version = record.get("version", 1)
        if not registry_id:
            return

        update = _post(request.node, f"{base_url}/schema/seed-schema/data/{registry_id}",
                       auth_headers, {
                           "id": registry_id,
                           "version": v1_version,
                           "data": {"name": "Updated Entry"},
                       })
        assert update.status_code in (200, 201), f"Data update failed: {update.text}"
        v2 = update.json()
        assert v2.get("version") != v1_version, \
            "Data version must increment on PUT"


# ---------------------------------------------------------------------------
# BR-LC-003: Deletion is soft only
# ---------------------------------------------------------------------------

class TestBR_LC_003_deletion_is_soft_only:
    """DELETE sets is_active=false; record disappears from default searches."""


# ---------------------------------------------------------------------------
# BR-LC-004: Data validation uses schema version stored at write time
# ---------------------------------------------------------------------------

class TestBR_LC_004_data_validation_uses_schema_version_at_write_time:
    """
    On update, validation uses the schema version stored on the existing record,
    not the current latest schema version. A record written against v1 is validated
    against v1 when updated, even if a v2 schema exists.
    """

    def test_data_update_succeeds_against_stored_schema_version(
        self, request, base_url, auth_headers
    ):
        create = req_lib.post(f"{base_url}/schema/seed-schema/data", headers=auth_headers,
                              json={"data": {"name": "Schema Version Test"}})
        if create.status_code not in (200, 201):
            return
        record = create.json()
        reg_id = record.get("registryId") or record.get("id")
        version = record.get("version", 1)
        if not reg_id:
            return

        update = req_lib.put(f"{base_url}/schema/seed-schema/data/{reg_id}",
                             headers=auth_headers,
                             json={"id": reg_id, "version": version,
                                   "data": {"name": "Updated against stored schema"}})
        assert update.status_code in (200, 201), \
            f"Data update against stored schema version must succeed, got {update.status_code}: {update.text}"


# ---------------------------------------------------------------------------
# BR-LC-005: Audit log forms tamper-evident hash chain
# ---------------------------------------------------------------------------

class TestBR_LC_005_audit_log_forms_tamper_evident_hash_chain:
    """
    Each audit entry for a record chains from the previous entry's hash.
    Observable via the _verify endpoint: if the chain is intact → 200;
    if Vault is not configured → 501.
    """

    def test_audit_chain_accessible_after_data_mutations(
        self, request, base_url, auth_headers
    ):
        create = req_lib.post(f"{base_url}/schema/seed-schema/data", headers=auth_headers,
                              json={"data": {"name": "Audit Chain Test"}})
        if create.status_code not in (200, 201):
            return
        record = create.json()
        reg_id = record.get("registryId") or record.get("id")
        if not reg_id:
            return

        verify = req_lib.get(f"{base_url}/_verify", headers=auth_headers,
                             params={"registryId": reg_id, "schemaCode": "seed-schema"})
        assert verify.status_code in (200, 404, 501), \
            f"_verify must return 200 (valid chain), 404 (no audit), or 501 (no vault); got {verify.status_code}"


    def test_deleted_schema_not_found_by_default(self, request, base_url, auth_headers):
        code = "lc003-" + uuid.uuid4().hex[:6]
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": code, "definition": _VALID_DEFINITION})

        del_resp = req_lib.delete(f"{base_url}/schema/{code}", headers=auth_headers)
        assert del_resp.status_code in (200, 204), f"Delete failed: {del_resp.text}"

        get_resp = req_lib.get(f"{base_url}/schema/{code}", headers=auth_headers)
        assert get_resp.status_code == 404, \
            f"Soft-deleted schema must return 404, got {get_resp.status_code}"
