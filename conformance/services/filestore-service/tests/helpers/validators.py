import jsonschema


def assert_gateway_headers(response, gateway_headers_spec):
    if not gateway_headers_spec:
        return
    for header, spec in gateway_headers_spec.items():
        present = header in response.headers
        if spec["required"]:
            assert present, f"Expected gateway header '{header}' is missing."
        if present:
            value = response.headers[header]
            if spec["type"] == int:
                assert value.isdigit(), f"Header '{header}' should be numeric, got: '{value}'"
            elif spec["type"] == str:
                assert isinstance(value, str) and len(value) > 0, \
                    f"Header '{header}' should be a non-empty string, got: '{value}'"


def assert_service_response_headers(response):
    content_type = response.headers.get("Content-Type", "")
    assert "application/json" in content_type, \
        f"Expected Content-Type application/json, got: {content_type}"


def assert_error_schema(body, error_schema):
    jsonschema.validate(instance=body, schema=error_schema)


def assert_required_fields(body, fields):
    for field in fields:
        assert field in body, f"Required field '{field}' missing from response body"


def assert_field_types(body, type_map):
    for field, expected_type in type_map.items():
        if field in body:
            assert isinstance(body[field], expected_type), (
                f"Field '{field}' expected {expected_type.__name__}, "
                f"got {type(body[field]).__name__}: {body[field]!r}"
            )


def assert_enum_values(body, enum_map):
    for field, allowed_values in enum_map.items():
        if field in body:
            assert body[field] in allowed_values, (
                f"Field '{field}' value '{body[field]}' not in allowed: {allowed_values}"
            )


def assert_document_category_shape(item):
    required = ["type", "code", "allowedFormats", "maxSize", "isSensitive"]
    for field in required:
        assert field in item, f"Required field '{field}' missing from DocumentCategory"
    assert isinstance(item["allowedFormats"], list), "allowedFormats must be a list"
    assert isinstance(item["isSensitive"], bool), "isSensitive must be a boolean"


def assert_storage_response_shape(body):
    assert "files" in body, "StorageResponse must have 'files' field"
    assert isinstance(body["files"], list), "'files' must be a list"
    for f in body["files"]:
        assert "fileStoreId" in f, "Each file object must have 'fileStoreId'"
        assert isinstance(f["fileStoreId"], str) and len(f["fileStoreId"]) > 0, \
            "fileStoreId must be a non-empty string"


def assert_file_metadata_shape(body):
    assert "fileStoreId" in body, "FileMetadata must have 'fileStoreId'"
    if "fileSize" in body:
        assert isinstance(body["fileSize"], int) and body["fileSize"] >= 0, \
            "fileSize must be a non-negative integer"


def assert_upload_response_shape(body):
    assert "preSignedUrl" in body, "UploadResponse must have 'preSignedUrl'"
    assert "fileStoreId" in body, "UploadResponse must have 'fileStoreId'"
    assert isinstance(body["preSignedUrl"], str), "preSignedUrl must be a string"
    assert isinstance(body["fileStoreId"], str), "fileStoreId must be a string"


def assert_download_urls_response_shape(body):
    assert isinstance(body, dict), "DownloadUrlsResponse must be an object"
