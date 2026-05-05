import requests as req_lib
from tests.helpers.curl_builder import attach_curl, attach_multipart_curl
from tests.helpers.validators import assert_gateway_headers
from tests.helpers.factories import (
    make_document_category,
    make_invalid_document_category,
    make_invalid_upload_request,
    make_dummy_text_file,
    make_files_param,
)

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}
ERROR_ARRAY_SCHEMA = {"type": "array", "items": SINGLE_ERROR_SCHEMA}


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _send_multipart(node, url, headers=None, files=None, data=None):
    attach_multipart_curl(node, url, headers or {}, files=files, fields=data)
    return req_lib.Session().post(url, headers=headers or {}, files=files or {}, data=data or {})


class TestDocumentCategoryNegativeContracts:
    def test_create_missing_required_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_invalid_document_category("missing_required"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_invalid_document_category("missing_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_code_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_invalid_document_category("missing_code"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_allowed_formats_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_invalid_document_category("missing_allowed_formats"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_max_size_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_invalid_document_category("missing_max_size"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_create_missing_is_sensitive_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_invalid_document_category("missing_is_sensitive"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_nonexistent_doc_code_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET",
                         f"{base_url}/document-categories/NONEXISTENT-DOC-XYZ-000",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_doc_code_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT",
                         f"{base_url}/document-categories/NONEXISTENT-DOC-XYZ-000",
                         headers=auth_headers,
                         json_body=make_document_category(doc_code="NONEXISTENT-DOC-XYZ-000"))
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_delete_nonexistent_doc_code_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "DELETE",
                         f"{base_url}/document-categories/NONEXISTENT-DOC-XYZ-000",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_on_search_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/document-categories")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_auth_on_create_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         json_body=make_document_category())
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer invalid-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/document-categories", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestUploadNegativeContracts:
    def test_upload_missing_file_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files={}, data={})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_upload_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        attach_multipart_curl(request.node, f"{base_url}/upload", {}, files=files)
        response = req_lib.Session().post(f"{base_url}/upload", files=files)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestMetadataNegativeContracts:
    def test_metadata_missing_file_store_id_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/metadata",
                         headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_metadata_nonexistent_file_store_id_returns_400_or_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/metadata",
                         headers=auth_headers,
                         params={"fileStoreId": "nonexistent-file-id-xyz-000"})
        assert response.status_code in (400, 404), \
            f"Expected 400 or 404, got {response.status_code}: {response.text}"
        assert_gateway_headers(response, gateway_headers_spec)


class TestTagNegativeContracts:
    def test_tag_missing_tag_param_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/tag",
                         headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_tag_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/tag",
                         params={"tag": "some-tag"})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestUploadUrlNegativeContracts:
    def test_upload_url_missing_required_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/upload-url",
                         headers=auth_headers,
                         json_body=make_invalid_upload_request("missing_required"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_upload_url_missing_file_name_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/upload-url",
                         headers=auth_headers,
                         json_body=make_invalid_upload_request("missing_file_name"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_upload_url_missing_content_type_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/upload-url",
                         headers=auth_headers,
                         json_body=make_invalid_upload_request("missing_content_type"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_upload_url_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/upload-url",
                         json_body=make_invalid_upload_request("missing_required"))
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestConfirmUploadNegativeContracts:
    def test_confirm_upload_empty_body_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/confirm-upload",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_confirm_upload_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/confirm-upload",
                         json_body={"fileStoreId": "some-id"})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestDownloadUrlsNegativeContracts:
    def test_download_urls_missing_file_store_ids_returns_400(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/download-urls",
                         headers=auth_headers)
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_download_urls_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/download-urls",
                         params={"fileStoreIds": "some-id"})
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)


class TestDownloadFileNegativeContracts:
    def test_download_nonexistent_file_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET",
                         f"{base_url}/nonexistent-file-store-id-xyz-000",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_download_missing_auth_returns_401(
        self, request, base_url, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/some-file-id")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)
