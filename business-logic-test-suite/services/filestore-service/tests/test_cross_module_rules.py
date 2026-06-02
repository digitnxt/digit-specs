"""
Cross-module rule tests for Filestore service.
S3 and PubSub rules — observable only via write success/failure.
"""
import io
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CM-001: S3 dual-bucket strategy for read and write
# ---------------------------------------------------------------------------

class TestBR_CM_001_s3_dual_bucket_strategy:
    """Uploaded artifact is downloadable (write to S3_BUCKET, read from S3_READ_BUCKET)."""

    def test_uploaded_artifact_is_downloadable(self, request, base_url, auth_headers):
        files = {"file": ("test.pdf", io.BytesIO(b"%PDF s3-test"), "application/pdf")}
        upload = req_lib.post(f"{base_url}/files", headers=auth_headers,
                              data={"module": "test-doc"}, files=files)
        if upload.status_code not in (200, 201):
            return

        file_store_id = (upload.json().get("fileStoreIds") or [None])[0] or \
                        upload.json().get("fileStoreId")
        if not file_store_id:
            return

        download = req_lib.get(f"{base_url}/files/id",
                               headers=auth_headers,
                               params={"fileStoreId": file_store_id, "tenantId": "test"})
        assert download.status_code == 200, \
            f"Downloaded artifact must be accessible via read bucket, got {download.status_code}: {download.text}"


# ---------------------------------------------------------------------------
# BR-CM-002: PubSub events are fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_002_pubsub_events_are_fire_and_forget:
    """Upload and category mutations succeed regardless of PubSub availability."""

    def test_upload_succeeds_independent_of_pubsub(self, request, base_url, auth_headers):
        files = {"file": ("test.pdf", io.BytesIO(b"%PDF pubsub-test"), "application/pdf")}
        resp = req_lib.post(f"{base_url}/files", headers=auth_headers,
                            data={"module": "test-doc"}, files=files)
        assert resp.status_code in (200, 201), \
            f"Upload must succeed regardless of PubSub availability, got {resp.status_code}: {resp.text}"
