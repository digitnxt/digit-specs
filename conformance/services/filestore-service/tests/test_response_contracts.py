import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl, attach_multipart_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_service_response_headers,
    assert_required_fields,
    assert_field_types,
    assert_document_category_shape,
    assert_storage_response_shape,
    assert_file_metadata_shape,
    assert_upload_response_shape,
)
from tests.helpers.factories import (
    make_doc_code,
    make_document_category,
    make_upload_request,
    make_dummy_text_file,
    make_dummy_png_file,
    make_dummy_pdf_file,
    make_files_param,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _send_multipart(node, url, headers, files, data=None):
    """POST multipart/form-data. Attaches a readable cURL to the node."""
    attach_multipart_curl(node, url, headers, files=files, fields=data)
    return req_lib.Session().post(url, headers=headers, files=files, data=data or {})


class TestDocumentCategoryCreateContract:
    def test_create_returns_201_with_document_category(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers, json_body=make_document_category())
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_document_category_shape(response.json())

    def test_create_response_echoes_submitted_code(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_doc_code()
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers, json_body=make_document_category(doc_code=code))
        assert response.status_code == 201
        assert response.json()["code"] == code

    def test_create_response_field_types(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers, json_body=make_document_category())
        assert response.status_code == 201
        body = response.json()
        assert_field_types(body, {"type": str, "code": str, "isSensitive": bool})
        assert isinstance(body["allowedFormats"], list)

    def test_create_sensitive_document_category(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers,
                         json_body=make_document_category(isSensitive=True))
        assert response.status_code == 201
        assert response.json()["isSensitive"] is True


class TestDocumentCategorySearchContract:
    def test_search_returns_200_with_array(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/document-categories",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list)

    def test_search_by_type_filter(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        payload = make_document_category(type="addressProof")
        _send(request.node, "POST", f"{base_url}/document-categories",
              headers=auth_headers, json_body=payload)

        response = _send(request.node, "GET", f"{base_url}/document-categories",
                         headers=auth_headers, params={"type": "addressProof"})
        assert response.status_code == 200
        assert isinstance(response.json(), list)

    def test_search_by_doc_code_filter(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_doc_code()
        _send(request.node, "POST", f"{base_url}/document-categories",
              headers=auth_headers, json_body=make_document_category(doc_code=code))

        response = _send(request.node, "GET", f"{base_url}/document-categories",
                         headers=auth_headers, params={"docCode": code})
        assert response.status_code == 200

    def test_search_items_have_required_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/document-categories",
                         headers=auth_headers)
        if response.status_code != 200:
            pytest.skip("Search returned non-200")
        for item in response.json():
            assert_document_category_shape(item)

    def test_search_by_is_sensitive_filter(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "GET", f"{base_url}/document-categories",
                         headers=auth_headers, params={"isSensitive": "false"})
        assert response.status_code == 200
        body = response.json()
        assert isinstance(body, list)
        for item in body:
            assert item["isSensitive"] is False


class TestDocumentCategoryGetContract:
    def test_get_by_code_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_doc_code()
        create_r = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers, json_body=make_document_category(doc_code=code))
        if create_r.status_code != 201:
            pytest.skip("Could not create document category for get test")

        response = _send(request.node, "GET", f"{base_url}/document-categories/{code}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert_document_category_shape(body)
        assert body["code"] == code


class TestDocumentCategoryUpdateContract:
    def test_update_returns_200_with_updated_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_doc_code()
        create_r = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers, json_body=make_document_category(doc_code=code))
        if create_r.status_code != 201:
            pytest.skip("Could not create document category for update test")

        updated = make_document_category(doc_code=code, description="Updated via conformance test",
                                         maxSize="10MB")
        response = _send(request.node, "PUT", f"{base_url}/document-categories/{code}",
                         headers=auth_headers, json_body=updated)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_document_category_shape(response.json())

    def test_update_nonexistent_returns_404(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "PUT", f"{base_url}/document-categories/NONEXISTENT-XYZ",
                         headers=auth_headers,
                         json_body=make_document_category(doc_code="NONEXISTENT-XYZ"))
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


class TestDocumentCategoryDeleteContract:
    def test_delete_returns_200_with_deleted_true(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        code = make_doc_code()
        create_r = _send(request.node, "POST", f"{base_url}/document-categories",
                         headers=auth_headers, json_body=make_document_category(doc_code=code))
        if create_r.status_code != 201:
            pytest.skip("Could not create document category for delete test")

        response = _send(request.node, "DELETE", f"{base_url}/document-categories/{code}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert "deleted" in body
        assert body["deleted"] is True


class TestDirectUploadContract:
    def test_upload_text_file_returns_201(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        data = {"tenantId": tenant_id, "module": "conformance-test", "tag": "test-txt"}

        response = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files, data=data)
        assert response.status_code == 201
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_storage_response_shape(response.json())

    def test_upload_png_file_returns_201(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_png_file())
        data = {"tenantId": tenant_id, "module": "conformance-test", "tag": "test-png"}

        response = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files, data=data)
        assert response.status_code == 201
        assert_storage_response_shape(response.json())

    def test_upload_pdf_file_returns_201(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_pdf_file())
        data = {"tenantId": tenant_id, "module": "conformance-test", "tag": "test-pdf"}

        response = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files, data=data)
        assert response.status_code == 201
        assert_storage_response_shape(response.json())

    def test_upload_response_has_file_store_id(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        response = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        assert response.status_code == 201
        returned_files = response.json()["files"]
        assert len(returned_files) >= 1
        assert all(isinstance(f["fileStoreId"], str) and f["fileStoreId"] for f in returned_files)

    def test_upload_response_tenant_id_matches(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        response = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        assert response.status_code == 201
        for f in response.json()["files"]:
            if "tenantId" in f:
                assert f["tenantId"] == tenant_id


class TestMetadataContract:
    def test_get_metadata_returns_200_with_file_store_id(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        upload_r = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        if upload_r.status_code != 201:
            pytest.skip("Upload failed — cannot test metadata")
        file_store_id = upload_r.json()["files"][0]["fileStoreId"]

        response = _send(request.node, "POST", f"{base_url}/metadata",
                         headers=auth_headers,
                         params={"fileStoreId": file_store_id, "tenantId": tenant_id})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_file_metadata_shape(response.json())

    def test_metadata_file_store_id_matches(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        upload_r = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        if upload_r.status_code != 201:
            pytest.skip("Upload failed")
        file_store_id = upload_r.json()["files"][0]["fileStoreId"]

        response = _send(request.node, "POST", f"{base_url}/metadata",
                         headers=auth_headers,
                         params={"fileStoreId": file_store_id})
        if response.status_code != 200:
            pytest.skip("Metadata returned non-200")
        assert response.json()["fileStoreId"] == file_store_id


class TestTagContract:
    def test_get_files_by_tag_returns_200(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        tag = f"conformance-{uuid.uuid4().hex[:8]}"
        files = make_files_param(make_dummy_text_file())
        _send_multipart(request.node, f"{base_url}/upload",
                        headers=auth_headers, files=files,
                        data={"tenantId": tenant_id, "tag": tag})

        response = _send(request.node, "POST", f"{base_url}/tag",
                         headers=auth_headers,
                         params={"tag": tag, "tenantId": tenant_id})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        body = response.json()
        assert "files" in body
        assert isinstance(body["files"], list)

    def test_tag_response_items_have_file_store_id(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        tag = f"conformance-{uuid.uuid4().hex[:8]}"
        files = make_files_param(make_dummy_text_file())
        _send_multipart(request.node, f"{base_url}/upload",
                        headers=auth_headers, files=files,
                        data={"tenantId": tenant_id, "tag": tag})

        response = _send(request.node, "POST", f"{base_url}/tag",
                         headers=auth_headers, params={"tag": tag})
        if response.status_code != 200:
            pytest.skip("Tag search returned non-200")
        for item in response.json().get("files", []):
            assert "fileStoreId" in item


class TestUploadUrlContract:
    def test_generate_upload_url_returns_200(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/upload-url",
                         headers=auth_headers, json_body=make_upload_request())
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert_upload_response_shape(response.json())

    def test_upload_url_response_has_required_fields(
        self, request, base_url, auth_headers, gateway_headers_spec
    ):
        response = _send(request.node, "POST", f"{base_url}/upload-url",
                         headers=auth_headers, json_body=make_upload_request())
        if response.status_code != 200:
            pytest.skip("upload-url returned non-200")
        assert_required_fields(response.json(), ["preSignedUrl", "fileStoreId"])
        assert_field_types(response.json(), {"preSignedUrl": str, "fileStoreId": str})


class TestDownloadUrlsContract:
    def test_get_download_urls_returns_200(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        upload_r = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        if upload_r.status_code != 201:
            pytest.skip("Upload failed — cannot test download-urls")
        file_store_id = upload_r.json()["files"][0]["fileStoreId"]

        response = _send(request.node, "GET", f"{base_url}/download-urls",
                         headers=auth_headers,
                         params={"fileStoreIds": file_store_id, "tenantId": tenant_id})
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), dict)


class TestDownloadFileContract:
    def test_download_uploaded_file_returns_200(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        upload_r = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        if upload_r.status_code != 201:
            pytest.skip("Upload failed — cannot test download")
        file_store_id = upload_r.json()["files"][0]["fileStoreId"]

        response = _send(request.node, "GET", f"{base_url}/{file_store_id}",
                         headers=auth_headers, params={"tenantId": tenant_id})
        assert response.status_code == 200
        assert_gateway_headers(response, gateway_headers_spec)
        assert len(response.content) > 0, "Downloaded file content must not be empty"

    def test_download_response_has_content_type_header(
        self, request, base_url, auth_headers, tenant_id, gateway_headers_spec
    ):
        files = make_files_param(make_dummy_text_file())
        upload_r = _send_multipart(request.node, f"{base_url}/upload",
                                   headers=auth_headers, files=files,
                                   data={"tenantId": tenant_id})
        if upload_r.status_code != 201:
            pytest.skip("Upload failed")
        file_store_id = upload_r.json()["files"][0]["fileStoreId"]

        response = _send(request.node, "GET", f"{base_url}/{file_store_id}",
                         headers=auth_headers)
        if response.status_code != 200:
            pytest.skip("Download returned non-200")
        assert "Content-Type" in response.headers, "Download response must include Content-Type"
