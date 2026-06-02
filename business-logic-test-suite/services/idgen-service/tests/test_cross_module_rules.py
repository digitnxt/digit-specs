"""
Cross-module rule tests for IDGen service.
All tests are opt-in: they skip when the required dep URL is absent.
"""
import uuid
import pytest
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
# BR-CM-001: Billing depends on IDGen bill-number template
# ---------------------------------------------------------------------------

class TestBR_CM_001_billing_depends_on_idgen_bill_number_template:
    """
    The Billing service requires a template with code == IDGEN_BILL_NUMBER_TEMPLATE_CODE
    to exist in IDGen. If absent, Billing bill creation returns 500.

    This test verifies the failure path: delete the template, attempt bill creation,
    then restore the template.
    """

    @pytest.fixture(autouse=True)
    def _skip_without_billing_url(self, request):
        if not request.config.getoption("--billing-url", default=None):
            pytest.skip("--billing-url not provided; cross-module test skipped")

    def test_bill_creation_fails_without_bill_number_template(
        self, request, base_url, auth_headers, service_urls
    ):
        import yaml, os
        billing_url = service_urls.get("--billing-url")
        env_map_path = os.path.join(os.path.dirname(__file__), "../../env_map.yaml")
        env_map = {}
        try:
            with open(env_map_path) as f:
                env_map = yaml.safe_load(f) or {}
        except FileNotFoundError:
            pass
        tpl_code = env_map.get("IDGEN_BILL_NUMBER_TEMPLATE_CODE", "BillNumber")

        existing = req_lib.get(
            f"{base_url}/template",
            params={"templateCode": tpl_code},
            headers=auth_headers,
        )
        assert existing.status_code == 200 and existing.json(), \
            f"Precondition: bill-number template '{tpl_code}' must exist. Check seed."

        req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": tpl_code, "version": "v1"},
            headers=auth_headers,
        )
        try:
            bill_resp = _post(request.node, f"{billing_url}/v3/bills/generate", auth_headers, {
                "consumerCode": "TEST-CONSUMER",
                "businessService": "TEST.SERVICE",
            })
            assert bill_resp.status_code == 500, \
                f"Expected 500 when bill-number template absent, got {bill_resp.status_code}: {bill_resp.text}"
        finally:
            req_lib.post(f"{base_url}/template", headers=auth_headers, json={
                "templateCode": tpl_code,
                "config": {
                    "template": "BILL-{DATE:yyyymmdd}-{SEQ}",
                    "sequence": {"scope": "DAILY", "start": 1, "padding": {"length": 5, "char": "0"}},
                },
            })


# ---------------------------------------------------------------------------
# BR-CM-002: Billing depends on IDGen receipt-number template
# ---------------------------------------------------------------------------

class TestBR_CM_002_billing_depends_on_idgen_receipt_number_template:
    """
    The Billing service requires a template with code == IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE
    to exist in IDGen. If absent, Billing payment creation returns 500.
    """

    @pytest.fixture(autouse=True)
    def _skip_without_billing_url(self, request):
        if not request.config.getoption("--billing-url", default=None):
            pytest.skip("--billing-url not provided; cross-module test skipped")

    def test_payment_creation_fails_without_receipt_number_template(
        self, request, base_url, auth_headers, service_urls
    ):
        import yaml, os
        billing_url = service_urls.get("--billing-url")
        env_map_path = os.path.join(os.path.dirname(__file__), "../../env_map.yaml")
        env_map = {}
        try:
            with open(env_map_path) as f:
                env_map = yaml.safe_load(f) or {}
        except FileNotFoundError:
            pass
        tpl_code = env_map.get("IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE", "ReceiptNumber")

        existing = req_lib.get(
            f"{base_url}/template",
            params={"templateCode": tpl_code},
            headers=auth_headers,
        )
        assert existing.status_code == 200 and existing.json(), \
            f"Precondition: receipt-number template '{tpl_code}' must exist. Check seed."

        req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": tpl_code, "version": "v1"},
            headers=auth_headers,
        )
        try:
            pay_resp = _post(request.node, f"{billing_url}/v3/payments", auth_headers, {
                "payment": {"billId": "NONEXISTENT-BILL"},
            })
            assert pay_resp.status_code == 500, \
                f"Expected 500 when receipt-number template absent, got {pay_resp.status_code}: {pay_resp.text}"
        finally:
            req_lib.post(f"{base_url}/template", headers=auth_headers, json={
                "templateCode": tpl_code,
                "config": {
                    "template": "RCPT-{DATE:yyyymmdd}-{SEQ}",
                    "sequence": {"scope": "DAILY", "start": 1, "padding": {"length": 5, "char": "0"}},
                },
            })


# ---------------------------------------------------------------------------
# BR-CM-003: PubSub publish is fire-and-forget
# ---------------------------------------------------------------------------

class TestBR_CM_003_pubsub_publish_is_fire_and_forget:
    """
    Template CREATE/UPDATE/DELETE succeed and return expected status codes even when
    the PubSub backend is unavailable. This is not directly testable from outside,
    but we verify that operations return success status regardless.
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
                f"Template creation must succeed regardless of PubSub availability, got {resp.status_code}: {resp.text}"
        finally:
            _cleanup(base_url, code, "v1", auth_headers)
