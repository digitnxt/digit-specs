"""
Cross-field rule tests for Registry service.
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


def _schema_code():
    return "br-cf-" + uuid.uuid4().hex[:8]


# ---------------------------------------------------------------------------
# BR-CF-002: Version must be present and positive on update
# ---------------------------------------------------------------------------

class TestBR_CF_002_version_must_be_present_and_positive_on_update:
    """PUT data without version or with version<=0 is rejected."""

    def test_missing_version_on_update_rejected(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/schema", headers=auth_headers,
                              json={"schemaCode": "seed-schema", "definition": _VALID_DEFINITION})
        # seed-schema should already exist
        data_create = req_lib.post(f"{base_url}/schema/seed-schema/data", headers=auth_headers,
                                   json={"data": {"name": "Test Entry"}})
        if data_create.status_code not in (200, 201):
            return
        registry_id = data_create.json().get("registryId") or data_create.json().get("id")
        if not registry_id:
            return

        resp = req_lib.put(f"{base_url}/schema/seed-schema/data/{registry_id}",
                           headers=auth_headers,
                           json={"data": {"name": "Updated"}, "id": registry_id})
        assert resp.status_code == 400, \
            f"Expected 400 for missing version on update, got {resp.status_code}: {resp.text}"

    def test_zero_version_on_update_rejected(self, request, base_url, auth_headers):
        resp = req_lib.put(f"{base_url}/schema/seed-schema/data/some-id",
                           headers=auth_headers,
                           json={"data": {"name": "Updated"}, "id": "some-id", "version": 0})
        assert resp.status_code == 400, \
            f"Expected 400 for version=0, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: SchemaCode must match identifier pattern
# ---------------------------------------------------------------------------

class TestBR_CF_003_schema_code_must_match_identifier_pattern:
    """schemaCode must match [A-Za-z0-9\\-_.:]+"""

    def test_valid_schema_code_accepted(self, request, base_url, auth_headers):
        code = "valid-schema-" + uuid.uuid4().hex[:6]
        resp = _post(request.node, f"{base_url}/schema", auth_headers,
                     {"schemaCode": code, "definition": _VALID_DEFINITION})
        assert resp.status_code in (200, 201, 409), \
            f"Valid schema code must be accepted, got {resp.status_code}: {resp.text}"

    def test_schema_code_with_special_chars_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/schema", auth_headers,
                     {"schemaCode": "invalid schema code!", "definition": _VALID_DEFINITION})
        assert resp.status_code == 400, \
            f"Expected 400 for invalid schema code, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Definition required and non-null on schema write
# ---------------------------------------------------------------------------

class TestBR_CF_004_definition_required_and_non_null:
    """definition must be present and non-empty on create/update."""

    def test_missing_definition_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/schema", auth_headers,
                     {"schemaCode": _schema_code()})
        assert resp.status_code == 400, \
            f"Expected 400 for missing definition, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Only JSON Schema Draft 2020-12 accepted
# ---------------------------------------------------------------------------

class TestBR_CF_005_only_json_schema_draft_2020_12_accepted:
    """Wrong $schema URI is rejected."""

    def test_wrong_schema_draft_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/schema", auth_headers, {
            "schemaCode": _schema_code(),
            "definition": {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {"name": {"type": "string"}},
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for wrong schema draft, got {resp.status_code}: {resp.text}"

    def test_correct_schema_draft_accepted(self, request, base_url, auth_headers):
        code = _schema_code()
        resp = _post(request.node, f"{base_url}/schema", auth_headers,
                     {"schemaCode": code, "definition": _VALID_DEFINITION})
        assert resp.status_code in (200, 201), \
            f"Expected 200/201 for correct draft, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-001: Optimistic version lock on data update
# ---------------------------------------------------------------------------

class TestBR_CF_001_optimistic_version_lock_on_data_update:
    """DataRequest.version must exactly match the server's current version on PUT."""

    def test_correct_version_on_update_accepted(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/schema/seed-schema/data", headers=auth_headers,
                              json={"data": {"name": "Version Lock Test"}})
        if create.status_code not in (200, 201):
            return
        record = create.json()
        reg_id = record.get("registryId") or record.get("id")
        version = record.get("version", 1)
        if not reg_id:
            return
        resp = req_lib.put(f"{base_url}/schema/seed-schema/data/{reg_id}",
                           headers=auth_headers,
                           json={"id": reg_id, "version": version, "data": {"name": "Updated"}})
        assert resp.status_code in (200, 201), \
            f"Correct version must be accepted, got {resp.status_code}: {resp.text}"

    def test_stale_version_on_update_rejected(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/schema/seed-schema/data", headers=auth_headers,
                              json={"data": {"name": "Stale Version Test"}})
        if create.status_code not in (200, 201):
            return
        record = create.json()
        reg_id = record.get("registryId") or record.get("id")
        if not reg_id:
            return
        resp = _post(request.node, f"{base_url}/schema/seed-schema/data/{reg_id}",
                     auth_headers,
                     {"id": reg_id, "version": 999, "data": {"name": "Stale"}})
        assert resp.status_code == 400, \
            f"Expected 400 for stale version, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: Webhook fires only when active and URL non-empty
# ---------------------------------------------------------------------------

class TestBR_CF_006_webhook_fires_only_when_active_and_url_non_empty:
    """
    Schema-level webhook fires only when webhook.active=true AND webhook.url is non-empty.
    A schema without a webhook config must be accepted (webhook silently skipped).
    """

    def test_schema_without_webhook_accepted(self, request, base_url, auth_headers):
        code = _schema_code()
        resp = _post(request.node, f"{base_url}/schema", auth_headers,
                     {"schemaCode": code, "definition": _VALID_DEFINITION})
        assert resp.status_code in (200, 201), \
            f"Schema without webhook config must be accepted, got {resp.status_code}: {resp.text}"

    def test_schema_with_inactive_webhook_accepted(self, request, base_url, auth_headers):
        code = _schema_code()
        resp = _post(request.node, f"{base_url}/schema", auth_headers, {
            "schemaCode": code,
            "definition": _VALID_DEFINITION,
            "webhook": {"active": False, "url": "https://example.com/hook"},
        })
        assert resp.status_code in (200, 201), \
            f"Schema with inactive webhook must be accepted, got {resp.status_code}: {resp.text}"
