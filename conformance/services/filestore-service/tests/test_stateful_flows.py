import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl, attach_multipart_curl
from tests.helpers.factories import (
    make_doc_code,
    make_document_category,
    make_upload_request,
    make_dummy_text_file,
    make_dummy_png_file,
    make_dummy_pdf_file,
    make_files_param,
)
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_document_category_shape,
    assert_storage_response_shape,
    assert_file_metadata_shape,
    assert_upload_response_shape,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _send_multipart(node, url, headers, files, data=None):
    attach_multipart_curl(node, url, headers, files=files, fields=data)
    return req_lib.Session().post(url, headers=headers, files=files, data=data or {})


def _cleanup_doc(base_url, doc_code, headers):
    try:
        req_lib.delete(f"{base_url}/document-categories/{doc_code}", headers=headers)
    except Exception:
        pass


class TestDocumentCategoryLifecycle:
    def test_create_get_update_delete(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_doc_code()
        payload = make_document_category(doc_code=code)

        try:
            # CREATE
            r = _send(request.node, "POST", f"{base_url}/document-categories",
                      headers=auth_headers, json_body=payload)
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_document_category_shape(r.json())
            assert r.json()["code"] == code

            # GET BY CODE
            r = _send(request.node, "GET", f"{base_url}/document-categories/{code}",
                      headers=auth_headers)
            assert r.status_code == 200, f"Get failed: {r.text}"
            assert r.json()["code"] == code

            # SEARCH — confirm it appears
            r = _send(request.node, "GET", f"{base_url}/document-categories",
                      headers=auth_headers, params={"docCode": code})
            assert r.status_code == 200
            results = r.json()
            assert any(item["code"] == code for item in results), \
                f"Code '{code}' not found in search results"

            # UPDATE
            updated = {**payload, "description": "Updated by conformance test", "maxSize": "10MB"}
            r = _send(request.node, "PUT", f"{base_url}/document-categories/{code}",
                      headers=auth_headers, json_body=updated)
            assert r.status_code == 200, f"Update failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert_document_category_shape(r.json())

            # DELETE
            r = _send(request.node, "DELETE", f"{base_url}/document-categories/{code}",
                      headers=auth_headers)
            assert r.status_code == 200, f"Delete failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert r.json().get("deleted") is True

            # VERIFY GONE — 404 after delete
            r = _send(request.node, "GET", f"{base_url}/document-categories/{code}",
                      headers=auth_headers)
            assert r.status_code == 404, "Deleted document category should return 404"

        finally:
            _cleanup_doc(base_url, code, auth_headers)

    def test_create_multiple_and_search_by_type(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        payloads = [make_document_category() for _ in range(3)]

        created_codes = []
        try:
            for payload in payloads:
                r = _send(request.node, "POST", f"{base_url}/document-categories",
                          headers=auth_headers, json_body=payload)
                assert r.status_code == 201, f"Create failed: {r.text}"
                created_codes.append(payload["code"])

            # Search by the type of the first one — must appear
            target_type = payloads[0]["type"]
            r = _send(request.node, "GET", f"{base_url}/document-categories",
                      headers=auth_headers, params={"type": target_type})
            assert r.status_code == 200
            assert_gateway_headers(r, gateway_headers_spec)
            returned_codes = {item["code"] for item in r.json()}
            assert payloads[0]["code"] in returned_codes, \
                f"Code '{payloads[0]['code']}' missing from type search"

        finally:
            for code in created_codes:
                _cleanup_doc(base_url, code, auth_headers)


class TestDirectUploadFlow:
    def test_upload_then_get_metadata(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        file_tuple = make_dummy_text_file()
        files = make_files_param(file_tuple)

        # UPLOAD
        r = _send_multipart(request.node, f"{base_url}/upload",
                            headers=auth_headers, files=files,
                            data={"tenantId": tenant_id, "module": "conformance", "tag": "meta-test"})
        assert r.status_code == 201, f"Upload failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_storage_response_shape(r.json())
        file_store_id = r.json()["files"][0]["fileStoreId"]

        # GET METADATA
        r = _send(request.node, "GET", f"{base_url}/metadata",
                  headers=auth_headers,
                  params={"fileStoreId": file_store_id, "tenantId": tenant_id})
        assert r.status_code == 200, f"Metadata failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_file_metadata_shape(r.json())
        assert r.json()["fileStoreId"] == file_store_id

    def test_upload_then_get_download_urls(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())

        r = _send_multipart(request.node, f"{base_url}/upload",
                            headers=auth_headers, files=files,
                            data={"tenantId": tenant_id, "module": "conformance-test"})
        assert r.status_code == 201, f"Upload failed: {r.text}"
        file_store_id = r.json()["files"][0]["fileStoreId"]

        # GET DOWNLOAD URLS
        r = _send(request.node, "GET", f"{base_url}/download-urls",
                  headers=auth_headers,
                  params={"fileStoreIds": file_store_id, "tenantId": tenant_id})
        assert r.status_code == 200, f"Download URLs failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert isinstance(r.json(), dict)

    def test_upload_then_download_file(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file(content="Hello from conformance test!"))

        r = _send_multipart(request.node, f"{base_url}/upload",
                            headers=auth_headers, files=files,
                            data={"tenantId": tenant_id, "module": "conformance-test"})
        assert r.status_code == 201, f"Upload failed: {r.text}"
        file_store_id = r.json()["files"][0]["fileStoreId"]

        # DOWNLOAD FILE
        r = _send(request.node, "GET", f"{base_url}/{file_store_id}",
                  headers=auth_headers, params={"tenantId": tenant_id})
        assert r.status_code == 200, f"Download failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert len(r.content) > 0, "Downloaded file must not be empty"
        assert "Content-Type" in r.headers

    def test_upload_multiple_files_retrieve_by_tag(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        tag = f"flow-{uuid.uuid4().hex[:8]}"
        uploaded_ids = []

        for i in range(2):
            files = make_files_param(make_dummy_text_file(content=f"File number {i}"))
            r = _send_multipart(request.node, f"{base_url}/upload",
                                headers=auth_headers, files=files,
                                data={"tenantId": tenant_id, "tag": tag, "module": "conformance-test"})
            assert r.status_code == 201, f"Upload {i} failed: {r.text}"
            uploaded_ids.append(r.json()["files"][0]["fileStoreId"])

        # SEARCH BY TAG
        r = _send(request.node, "GET", f"{base_url}/tag",
                  headers=auth_headers,
                  params={"tag": tag, "tenantId": tenant_id})
        assert r.status_code == 200, f"Tag search failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        files_by_tag = r.json().get("files", [])
        assert len(files_by_tag) >= 2, \
            f"Expected at least 2 files with tag '{tag}', got {len(files_by_tag)}"

    def test_upload_different_file_types(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        file_factories = [make_dummy_text_file, make_dummy_png_file, make_dummy_pdf_file]

        for make_file in file_factories:
            files = make_files_param(make_file())
            r = _send_multipart(request.node, f"{base_url}/upload",
                                headers=auth_headers, files=files,
                                data={"tenantId": tenant_id, "module": "conformance-test"})
            assert r.status_code == 201, f"Upload of {make_file.__name__} failed: {r.text}"
            assert_storage_response_shape(r.json())


class TestSignedUploadFlow:
    def test_generate_upload_url_returns_presigned_url(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        r = _send(request.node, "POST", f"{base_url}/upload-url",
                  headers=auth_headers, json_body=make_upload_request())
        assert r.status_code == 200, f"Upload URL generation failed: {r.text}"
        assert_gateway_headers(r, gateway_headers_spec)
        assert_upload_response_shape(r.json())
        body = r.json()
        assert len(body["fileStoreId"]) > 0
        assert len(body["preSignedUrl"]) > 0

    def test_confirm_upload_with_valid_id_returns_status(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        # Generate a fileStoreId via upload-url then confirm it
        r = _send(request.node, "POST", f"{base_url}/upload-url",
                  headers=auth_headers, json_body=make_upload_request())
        if r.status_code != 200:
            pytest.skip("Could not generate upload URL")
        file_store_id = r.json()["fileStoreId"]

        r = _send(request.node, "POST", f"{base_url}/confirm-upload",
                  headers=auth_headers, json_body={"fileStoreId": file_store_id})
        # File was never actually uploaded to the signed URL, so status may be INVALID or 400/404
        assert r.status_code in (200, 400, 404), \
            f"Unexpected status {r.status_code}: {r.text}"
        if r.status_code == 200:
            body = r.json()
            assert "status" in body
            assert body["status"] in ("VALID", "INVALID"), \
                f"status must be VALID or INVALID, got: {body['status']!r}"
