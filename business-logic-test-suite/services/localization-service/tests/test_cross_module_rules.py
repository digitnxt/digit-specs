"""
Cross-module rule tests for Localization service.
PubSub is fire-and-forget; observable only by write operation success.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _unique_code():
    return "br-cm-" + uuid.uuid4().hex[:8]


# ---------------------------------------------------------------------------
# BR-CM-001: PubSub events are fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_001_pubsub_events_are_fire_and_forget:
    """Write operations succeed regardless of PubSub availability."""

    def test_create_returns_success_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        code = _unique_code()
        resp = _post(request.node, f"{base_url}/messages", auth_headers, {
            "messages": [{"code": code, "message": "PubSub test", "module": "test", "locale": "en_IN"}],
        })
        assert resp.status_code in (200, 201), \
            f"Create must succeed regardless of PubSub availability, got {resp.status_code}: {resp.text}"
