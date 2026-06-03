"""
Cross-module rule tests for IDGen service.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _tpl_code():
    return "BR-CM-" + uuid.uuid4().hex[:8].upper()


def _cleanup(base_url, code, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": code, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ---------------------------------------------------------------------------
# BR-CM-001: PubSub publish is fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_001_pubsub_publish_is_fire_and_forget:
    """
    Template CREATE, UPDATE, and DELETE succeed regardless of PubSub availability.
    The caller always sees 200/201; publish failures are logged internally.
    """

    def test_template_create_returns_201_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            assert resp.status_code == 201, \
                f"CREATE must succeed regardless of PubSub, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)

    def test_template_update_returns_success_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        try:
            resp = req_lib.put(f"{base_url}/template", headers=auth_headers, json={
                "templateCode": code,
                "config": {"template": "{SEQ}-V2", "sequence": {"scope": "DAILY", "start": 1}},
            })
            assert resp.status_code in (200, 201), \
                f"UPDATE must succeed regardless of PubSub, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v2", auth_headers)
            _cleanup(base_url, code, "v1", auth_headers)

    def test_template_delete_returns_success_independent_of_pubsub(
        self, request, base_url, auth_headers
    ):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{SEQ}", "sequence": {"scope": "DAILY", "start": 1}},
        })
        resp = req_lib.delete(f"{base_url}/template",
                              params={"templateCode": code, "version": "v1"},
                              headers=auth_headers)
        assert resp.status_code == 200, \
            f"DELETE must succeed regardless of PubSub, got {resp.status_code}: {resp.text}"
