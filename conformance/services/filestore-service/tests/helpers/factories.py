"""
Test data factories for the filestore service.

Dummy file generators produce in-memory bytes — no disk I/O required.
"""

import io
import uuid


def _uid():
    return uuid.uuid4().hex[:8].upper()


# ── Document category ──────────────────────────────────────────────────────

def make_doc_code():
    return f"DOC-{_uid()}"


def make_document_category(doc_code=None, **overrides):
    """Valid DocumentCategory payload. Required: type, code, allowedFormats, maxSize, isSensitive."""
    code = doc_code or make_doc_code()
    base = {
        "type": f"type-{_uid()}",
        "code": code,
        "allowedFormats": ["pdf", "jpg", "png"],
        "maxSize": "5MB",
        "isSensitive": False,
    }
    return {**base, **overrides}


# ── Upload request (signed URL flow) ──────────────────────────────────────

def make_upload_request(**overrides):
    """Valid UploadRequest body for POST /upload-url."""
    base = {
        "fileName": f"test-{_uid()}.pdf",
        "contentType": "application/pdf",
        "module": "conformance-test",
        "tag": f"tag-{_uid()}",
    }
    return {**base, **overrides}


def make_confirm_upload_request(file_store_id):
    return {"fileStoreId": file_store_id}


# ── Dummy in-memory files ──────────────────────────────────────────────────
# Each function returns (filename: str, content: bytes, content_type: str).
# Use make_files_param() to convert to requests `files=` format.

_MINIMAL_PNG = bytes([
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
    0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
    0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
    0x00, 0x00, 0x02, 0x00, 0x01, 0xE2, 0x21, 0xBC,
    0x33, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
    0x44, 0xAE, 0x42, 0x60, 0x82,
])

_MINIMAL_PDF = b"""%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/MediaBox[0 0 3 3]>>endobj
xref
0 4
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
trailer<</Size 4/Root 1 0 R>>
startxref
190
%%EOF"""


def make_dummy_text_file(content=None):
    """Returns (filename, bytes, content_type) for a plain-text file."""
    name = f"test-{_uid()}.txt"
    data = (content or f"Conformance test file {_uid()}").encode("utf-8")
    return name, data, "text/plain"


def make_dummy_png_file():
    """Returns (filename, bytes, content_type) for a minimal 1×1 PNG."""
    return f"test-{_uid()}.png", _MINIMAL_PNG, "image/png"


def make_dummy_pdf_file():
    """Returns (filename, bytes, content_type) for a minimal PDF."""
    return f"test-{_uid()}.pdf", _MINIMAL_PDF, "application/pdf"


def make_files_param(file_tuple):
    """Convert (name, content, content_type) to a requests `files=` dict."""
    name, data, content_type = file_tuple
    return {"file": (name, io.BytesIO(data), content_type)}


# ── Invalid payloads ───────────────────────────────────────────────────────

def make_invalid_document_category(strategy="missing_required"):
    strategies = {
        "missing_required":        {},
        "missing_type":            {"code": "DOC-X", "allowedFormats": ["pdf"], "maxSize": "5MB", "isSensitive": False},
        "missing_code":            {"type": "identity", "allowedFormats": ["pdf"], "maxSize": "5MB", "isSensitive": False},
        "missing_allowed_formats": {"type": "identity", "code": "DOC-X", "maxSize": "5MB", "isSensitive": False},
        "missing_max_size":        {"type": "identity", "code": "DOC-X", "allowedFormats": ["pdf"], "isSensitive": False},
        "missing_is_sensitive":    {"type": "identity", "code": "DOC-X", "allowedFormats": ["pdf"], "maxSize": "5MB"},
        "invalid_max_size_format": {"type": "identity", "code": "DOC-X", "allowedFormats": ["pdf"], "maxSize": "not-a-size", "isSensitive": False},
    }
    return strategies.get(strategy, {})


def make_invalid_upload_request(strategy="missing_required"):
    strategies = {
        "missing_required":     {},
        "missing_file_name":    {"contentType": "application/pdf", "module": "test", "tag": "tag-x"},
        "missing_content_type": {"fileName": "test.pdf", "module": "test", "tag": "tag-x"},
        "missing_module":       {"fileName": "test.pdf", "contentType": "application/pdf", "tag": "tag-x"},
        "missing_tag":          {"fileName": "test.pdf", "contentType": "application/pdf", "module": "test"},
    }
    return strategies.get(strategy, {})
