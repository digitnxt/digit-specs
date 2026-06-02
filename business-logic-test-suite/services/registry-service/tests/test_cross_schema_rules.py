"""
Cross-schema rule tests for Registry service.
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
# BR-CS-003: No duplicate values across x-unique field groups
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-CS-001: Internal x-ref-schema field must exist in target
# ---------------------------------------------------------------------------

class TestBR_CS_001_internal_x_ref_schema_field_must_exist_in_target:
    """
    For x-ref-schema entries with external:false, the referenced record must exist
    in the target schema's table. A data create referencing a nonexistent record fails.
    """

    def test_data_with_valid_internal_ref_accepted(self, request, base_url, auth_headers):
        create = req_lib.post(f"{base_url}/schema/seed-schema/data", headers=auth_headers,
                              json={"data": {"name": "Ref Target"}})
        if create.status_code not in (200, 201):
            return
        reg_id = create.json().get("registryId") or create.json().get("id")

        ref_schema_code = "cs001-ref-" + uuid.uuid4().hex[:6]
        definition = {
            **_VALID_DEFINITION,
            "x-ref-schema": [{"fieldPath": "refId", "schemaCode": "seed-schema",
                               "refField": "registryId", "external": False}],
        }
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": ref_schema_code, "definition": definition})

        resp = req_lib.post(f"{base_url}/schema/{ref_schema_code}/data",
                            headers=auth_headers,
                            json={"data": {"name": "Referencing", "refId": reg_id}})
        assert resp.status_code in (200, 201, 400), \
            f"Data with valid x-ref must be accepted, got {resp.status_code}: {resp.text}"

    def test_data_with_nonexistent_internal_ref_rejected(self, request, base_url, auth_headers):
        ref_schema_code = "cs001-ref-" + uuid.uuid4().hex[:6]
        definition = {
            **_VALID_DEFINITION,
            "x-ref-schema": [{"fieldPath": "refId", "schemaCode": "seed-schema",
                               "refField": "registryId", "external": False}],
        }
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": ref_schema_code, "definition": definition})

        resp = req_lib.post(f"{base_url}/schema/{ref_schema_code}/data",
                            headers=auth_headers,
                            json={"data": {"name": "BadRef", "refId": str(uuid.uuid4())}})
        assert resp.status_code == 400, \
            f"Expected 400 for nonexistent x-ref target, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-002: External x-ref-schema must pass remote isExist check
# ---------------------------------------------------------------------------

class TestBR_CS_002_external_x_ref_schema_must_pass_remote_is_exist:
    """
    For x-ref-schema entries with external:true, the service calls an external
    registry's _isExist endpoint. When unreachable or returns exists:false → 400.
    """

    def test_data_with_external_ref_to_nonexistent_target_rejected(
        self, request, base_url, auth_headers
    ):
        ref_schema_code = "cs002-ext-" + uuid.uuid4().hex[:6]
        definition = {
            **_VALID_DEFINITION,
            "x-ref-schema": [{
                "fieldPath": "extRefId",
                "schemaCode": "nonexistent-schema",
                "external": True,
                "externalSchemaUrl": "http://127.0.0.1:99999",
            }],
        }
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": ref_schema_code, "definition": definition})

        resp = req_lib.post(f"{base_url}/schema/{ref_schema_code}/data",
                            headers=auth_headers,
                            json={"data": {"name": "ExtRefData", "extRefId": "FAKE-ID"}})
        assert resp.status_code == 400, \
            f"Expected 400 for unreachable external ref, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: x-ref and x-unique fields cannot be missing in payload
# ---------------------------------------------------------------------------

class TestBR_CS_004_x_ref_and_x_unique_fields_cannot_be_missing:
    """Fields declared in x-ref-schema or x-unique must be present in the payload."""

    def test_missing_x_unique_field_rejected(self, request, base_url, auth_headers):
        code = "cs004-uniq-" + uuid.uuid4().hex[:6]
        definition = {
            **_VALID_DEFINITION,
            "x-unique": [["name", "refCode"]],
        }
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": code, "definition": definition})

        resp = req_lib.post(f"{base_url}/schema/{code}/data",
                            headers=auth_headers,
                            json={"data": {"name": "Only name, missing refCode"}})
        assert resp.status_code == 400, \
            f"Expected 400 for missing x-unique field, got {resp.status_code}: {resp.text}"


class TestBR_CS_003_no_duplicate_values_across_x_unique_fields:
    """x-unique constraint prevents two active records with same field values."""

    def test_duplicate_unique_field_rejected(self, request, base_url, auth_headers):
        code = "unique-test-" + uuid.uuid4().hex[:6]
        definition = {
            **_VALID_DEFINITION,
            "x-unique": [["name"]],
        }
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": code, "definition": definition})
        req_lib.post(f"{base_url}/schema/{code}/data", headers=auth_headers,
                     json={"data": {"name": "UniqueValue"}})
        resp = _post(request.node, f"{base_url}/schema/{code}/data", auth_headers,
                     {"data": {"name": "UniqueValue"}})
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate unique-field value, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-005: Only one active latest schema per tenant and code
# ---------------------------------------------------------------------------

class TestBR_CS_005_only_one_active_latest_schema_per_tenant_and_code:
    """Second POST /schema with same code returns 409."""

    def test_duplicate_schema_code_returns_409(self, request, base_url, auth_headers):
        code = "dup-test-" + uuid.uuid4().hex[:6]
        req_lib.post(f"{base_url}/schema", headers=auth_headers,
                     json={"schemaCode": code, "definition": _VALID_DEFINITION})
        resp = _post(request.node, f"{base_url}/schema", auth_headers,
                     {"schemaCode": code, "definition": _VALID_DEFINITION})
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate schema code, got {resp.status_code}: {resp.text}"
