"""
Cross-field rule tests for Filestore service.
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


def _cat_type():
    return "CAT-CF-" + uuid.uuid4().hex[:6].upper()


# ---------------------------------------------------------------------------
# BR-CF-001: File extension must match allowed formats
# ---------------------------------------------------------------------------

class TestBR_CF_001_file_extension_must_match_allowed_formats:
    """Uploaded file extension must be in the category's allowedFormats list."""

    def test_allowed_extension_accepted(self, request, base_url, auth_headers):
        files = {"file": ("test.pdf", io.BytesIO(b"%PDF test"), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code in (200, 201), \
            f"PDF upload should be accepted (test-doc allows .pdf), got {resp.status_code}: {resp.text}"

    def test_disallowed_extension_rejected(self, request, base_url, auth_headers):
        files = {"file": ("test.exe", io.BytesIO(b"binary"), "application/octet-stream")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code == 400, \
            f"Expected 400 for .exe extension, got {resp.status_code}: {resp.text}"

    def test_extension_check_is_case_insensitive(self, request, base_url, auth_headers):
        files = {"file": ("test.PDF", io.BytesIO(b"%PDF test"), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code in (200, 201), \
            f"Extension check must be case-insensitive (.PDF == .pdf), got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: DocumentCategory type uniqueness per tenant
# ---------------------------------------------------------------------------

class TestBR_CF_004_document_category_type_uniqueness_per_tenant:
    """Duplicate (tenantid, type) for DocumentCategory returns 409."""

    def test_unique_category_type_accepted(self, request, base_url, auth_headers):
        cat_type = _cat_type()
        resp = req_lib.post(f"{base_url}/files/test/document-categories", headers=auth_headers,
                            json={
                                "type": cat_type, "code": cat_type.lower(),
                                "allowedFormats": [".pdf"],
                                "minSize": "1KB", "maxSize": "5MB",
                                "isActive": True,
                            })
        assert resp.status_code in (200, 201), f"Expected 200/201, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: File size must be within category bounds
# ---------------------------------------------------------------------------

class TestBR_CF_002_file_size_within_category_bounds:
    """Uploaded file size must be >= minSize and <= maxSize of the DocumentCategory."""

    def test_file_above_max_size_rejected(self, request, base_url, auth_headers):
        import io
        large_content = b"x" * (11 * 1024 * 1024)  # 11 MB > maxSize 10 MB
        files = {"file": ("big.pdf", io.BytesIO(large_content), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code == 400, \
            f"Expected 400 for file exceeding maxSize, got {resp.status_code}: {resp.text}"

    def test_file_within_bounds_accepted(self, request, base_url, auth_headers):
        import io
        content = b"%PDF-1.4 " + b"x" * 1024  # ~1 KB — within [1KB, 10MB]
        files = {"file": ("ok.pdf", io.BytesIO(content), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code in (200, 201), \
            f"File within size bounds must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Multipart form total capped at 32 MB
# ---------------------------------------------------------------------------

class TestBR_CF_003_multipart_form_total_capped_at_32_mb:
    """
    Total multipart form body > 32 MB is rejected before file content is processed.
    We test this with a file just over 32 MB; the test may time out or be rejected
    at the transport layer — either a 400 or a connection reset is the expected outcome.
    """

    def test_file_above_32mb_rejected(self, request, base_url, auth_headers):
        import io
        # 33 MB — exceeds the 32 MB multipart cap
        content = b"x" * (33 * 1024 * 1024)
        files = {"file": ("huge.pdf", io.BytesIO(content), "application/pdf")}
        try:
            resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                                data={"module": "test-doc"}, files=files, timeout=30)
            assert resp.status_code == 400, \
                f"Expected 400 for 33 MB upload, got {resp.status_code}: {resp.text}"
        except req_lib.exceptions.ConnectionError:
            pass  # Connection reset by server is also acceptable


    def test_duplicate_category_type_returns_409(self, request, base_url, auth_headers):
        cat_type = _cat_type()
        body = {
            "type": cat_type, "code": cat_type.lower(),
            "allowedFormats": [".pdf"],
            "minSize": "1KB", "maxSize": "5MB",
            "isActive": True,
        }
        req_lib.post(f"{base_url}/files/test/document-categories",
                     headers=auth_headers, json=body)
        resp = req_lib.post(f"{base_url}/files/test/document-categories",
                            headers=auth_headers, json=body)
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate category type, got {resp.status_code}: {resp.text}"
