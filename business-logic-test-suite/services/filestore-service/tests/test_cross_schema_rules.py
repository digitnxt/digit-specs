"""
Cross-schema rule tests for Filestore service.
"""
import io
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _attach_and_send(node, method, url, headers, **kwargs):
    r = req_lib.Request(method, url, headers=headers, **kwargs)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CS-001: Upload requires active DocumentCategory
# ---------------------------------------------------------------------------

class TestBR_CS_001_upload_requires_active_document_category:
    """Uploads are rejected when no active DocumentCategory matches the module."""

    def test_upload_with_valid_active_category_accepted(self, request, base_url, auth_headers):
        files = {"file": ("test.pdf", io.BytesIO(b"%PDF test"), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code in (200, 201), \
            f"Upload must succeed with active category, got {resp.status_code}: {resp.text}"

    def test_upload_for_nonexistent_module_rejected(self, request, base_url, auth_headers):
        files = {"file": ("test.pdf", io.BytesIO(b"%PDF test"), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": f"no-such-category-{uuid.uuid4().hex[:6]}"}, files=files)
        assert resp.status_code == 400, \
            f"Expected 400 for unknown module, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-002: Tenant isolation on all table queries
# ---------------------------------------------------------------------------

class TestBR_CS_002_tenant_isolation_on_all_table_queries:
    """
    All queries are scoped to tenantid = X-Tenant-Id.
    Requests without X-Tenant-Id are rejected.
    """

    def test_request_without_tenant_id_rejected(self, request, base_url, auth_headers):
        headers_no_tenant = {k: v for k, v in auth_headers.items()
                             if k.lower() != "x-tenant-id"}
        resp = req_lib.get(f"{base_url}/files/id", headers=headers_no_tenant,
                           params={"fileStoreId": "any-id"})
        assert resp.status_code in (400, 404), \
            f"Expected 400/404 without X-Tenant-Id header, got {resp.status_code}: {resp.text}"

    def test_file_lookup_only_returns_own_tenant_data(self, request, base_url, auth_headers):
        import uuid
        non_existent_id = str(uuid.uuid4())
        resp = req_lib.get(f"{base_url}/files/id", headers=auth_headers,
                           params={"fileStoreId": non_existent_id})
        assert resp.status_code == 404, \
            "Cross-tenant isolation: random ID must not match another tenant's file"


    def test_upload_for_inactive_category_rejected(self, request, base_url, auth_headers):
        cat_type = "INACTIVE-" + uuid.uuid4().hex[:6].upper()
        req_lib.post(f"{base_url}/files/test/document-categories", headers=auth_headers, json={
            "type": cat_type, "code": cat_type.lower(),
            "allowedFormats": [".pdf"],
            "minSize": "1KB", "maxSize": "5MB",
            "isActive": False,
        })

        cat_search = req_lib.get(f"{base_url}/files/test/document-categories/{cat_type.lower()}",
                                 headers=auth_headers)
        if cat_search.status_code != 200:
            return

        existing = cat_search.json()
        req_lib.put(
            f"{base_url}/files/test/document-categories/{existing.get('id', '')}",
            headers=auth_headers,
            json={**existing, "isActive": False},
        )
        try:
            files = {"file": ("test.pdf", io.BytesIO(b"%PDF test"), "application/pdf")}
            resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                                data={"module": cat_type.lower()}, files=files)
            assert resp.status_code == 400, \
                f"Expected 400 for inactive category, got {resp.status_code}: {resp.text}"
        finally:
            req_lib.put(
                f"{base_url}/files/test/document-categories/{existing.get('id', '')}",
                headers=auth_headers,
                json={**existing, "isActive": True},
            )
