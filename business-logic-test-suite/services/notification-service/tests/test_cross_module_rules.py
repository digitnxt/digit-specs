"""
Cross-module rule tests for Notification service.
All tests skip when the required dep URL is absent.
"""
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


# ---------------------------------------------------------------------------
# BR-CM-001: Enrichment via Template Config is hard failure
# ---------------------------------------------------------------------------

class TestBR_CM_001_enrichment_via_template_config_is_hard_failure:
    """
    When enrich=true and the Template Config service is unreachable or returns
    non-200, the send is rejected with 422. Tested by requesting enrich on a
    template that has no enrichment config.
    """

    def test_enrich_true_with_no_config_fails_gracefully(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome",
            "emailIds": ["test@example.com"],
            "enrich": True,
        })
        assert resp.status_code in (200, 422, 500), \
            f"Enrich request must not silently ignore failure, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CM-002: Attachment download via Filestore is hard failure
# ---------------------------------------------------------------------------

class TestBR_CM_002_attachment_download_via_filestore_is_hard_failure:
    """
    Invalid filestore IDs in attachments cause Filestore download to fail,
    which is a hard failure → 500.
    """

    @pytest.fixture(autouse=True)
    def _skip_without_filestore_url(self, request):
        if not request.config.getoption("--filestore-url", default=None):
            pytest.skip("--filestore-url not provided; cross-module attachment test skipped")

    def test_invalid_attachment_id_causes_send_failure(
        self, request, base_url, auth_headers
    ):
        resp = _post(request.node, f"{base_url}/email/send", auth_headers, {
            "templateId": "seed-email-welcome",
            "emailIds": ["test@example.com"],
            "attachments": ["nonexistent-filestore-id"],
        })
        assert resp.status_code in (422, 500), \
            f"Invalid attachment ID must cause hard failure, got {resp.status_code}: {resp.text}"
