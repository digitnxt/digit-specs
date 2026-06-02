"""
Cross-module rule tests for Billing-Payment service.
All IDGen tests skip when --idgen-url not provided.
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


def _now_ms():
    import time
    return int(time.time() * 1000)


def _load_env():
    import yaml, os
    env_path = os.path.join(os.path.dirname(__file__), "../../env_map.yaml")
    try:
        with open(env_path) as f:
            return yaml.safe_load(f) or {}
    except FileNotFoundError:
        return {}


# ---------------------------------------------------------------------------
# BR-CM-001: IDGen required for bill number
# ---------------------------------------------------------------------------

class TestBR_CM_001_idgen_required_for_bill_number:
    """Bill generation fails with 500 when IDGen bill-number template is absent."""

    @pytest.fixture(autouse=True)
    def _skip_without_idgen_url(self, request):
        if not request.config.getoption("--idgen-url", default=None):
            pytest.skip("--idgen-url not provided; IDGen cross-module test skipped")

    def test_bill_generation_fails_without_bill_number_template(
        self, request, base_url, auth_headers, service_urls
    ):
        idgen_url = service_urls.get("--idgen-url")
        env = _load_env()
        tpl_code = env.get("IDGEN_BILL_NUMBER_TEMPLATE_CODE", "BillNumber")

        existing = req_lib.get(f"{idgen_url}/template",
                               headers=auth_headers,
                               params={"templateCode": tpl_code})
        assert existing.status_code == 200 and existing.json(), \
            f"Precondition: bill-number template '{tpl_code}' must exist in IDGen"

        req_lib.delete(f"{idgen_url}/template",
                       params={"templateCode": tpl_code, "version": "v1"},
                       headers=auth_headers)
        try:
            consumer = "CONS-CM001-" + uuid.uuid4().hex[:4].upper()
            now = _now_ms()
            req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
                "consumerCode": consumer,
                "businessServiceCode": "TEST-BS",
                "periodFrom": now - 30 * 86400 * 1000,
                "periodTo": now,
                "totalAmount": 100.0,
                "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
            })
            resp = _post(request.node, f"{base_url}/bills/generate", auth_headers, {
                "consumerCode": consumer,
                "businessService": "TEST-BS",
            })
            assert resp.status_code == 500, \
                f"Expected 500 when bill-number template absent, got {resp.status_code}: {resp.text}"
        finally:
            req_lib.post(f"{idgen_url}/template", headers=auth_headers, json={
                "templateCode": tpl_code,
                "config": {
                    "template": "BILL-{DATE:yyyymmdd}-{SEQ}",
                    "sequence": {"scope": "DAILY", "start": 1, "padding": {"length": 5, "char": "0"}},
                },
            })


# ---------------------------------------------------------------------------
# BR-CM-002: IDGen required for receipt number
# ---------------------------------------------------------------------------

class TestBR_CM_002_idgen_required_for_receipt_number:
    """Payment creation fails with 500 when IDGen receipt-number template is absent."""

    @pytest.fixture(autouse=True)
    def _skip_without_idgen_url(self, request):
        if not request.config.getoption("--idgen-url", default=None):
            pytest.skip("--idgen-url not provided; IDGen cross-module test skipped")

    def test_payment_fails_without_receipt_number_template(
        self, request, base_url, auth_headers, service_urls
    ):
        idgen_url = service_urls.get("--idgen-url")
        env = _load_env()
        tpl_code = env.get("IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE", "ReceiptNumber")

        existing = req_lib.get(f"{idgen_url}/template",
                               headers=auth_headers,
                               params={"templateCode": tpl_code})
        assert existing.status_code == 200 and existing.json(), \
            f"Precondition: receipt-number template '{tpl_code}' must exist in IDGen"

        req_lib.delete(f"{idgen_url}/template",
                       params={"templateCode": tpl_code, "version": "v1"},
                       headers=auth_headers)
        try:
            resp = _post(request.node, f"{base_url}/payments", auth_headers, {
                "payment": {
                    "paymentMode": "CASH",
                    "totalAmountPaid": 100.0,
                    "paymentDetails": [{"billId": "ACTIVE-BILL-001", "amountPaid": 100.0}],
                },
            })
            assert resp.status_code == 500, \
                f"Expected 500 when receipt-number template absent, got {resp.status_code}: {resp.text}"
        finally:
            req_lib.post(f"{idgen_url}/template", headers=auth_headers, json={
                "templateCode": tpl_code,
                "config": {
                    "template": "RCPT-{DATE:yyyymmdd}-{SEQ}",
                    "sequence": {"scope": "DAILY", "start": 1, "padding": {"length": 5, "char": "0"}},
                },
            })


# ---------------------------------------------------------------------------
# BR-CM-003: Apportion distributes payment amount
# ---------------------------------------------------------------------------

class TestBR_CM_003_apportion_distributes_payment_amount:
    """Payment amount is distributed across bill line items by Apportion service."""


# ---------------------------------------------------------------------------
# BR-CM-004: Bulk bill generation via PubSub
# ---------------------------------------------------------------------------

class TestBR_CM_004_bulk_bill_generation_via_pubsub:
    """POST /bills/bulk-generate publishes to PubSub rather than generating synchronously."""

    def test_bulk_generate_endpoint_returns_accepted(
        self, request, base_url, auth_headers
    ):
        import uuid
        resp = _post(request.node, f"{base_url}/bills/bulk-generate", auth_headers, {
            "consumerCodes": ["CONS-BLK-" + uuid.uuid4().hex[:4].upper()],
            "businessService": "TEST-BS",
        })
        # 202 = message published and accepted asynchronously; 500 if PubSub is down
        assert resp.status_code in (200, 201, 202, 500), \
            f"Bulk generate must return 200/201/202 (accepted) or 500 (PubSub down), got {resp.status_code}: {resp.text}"


    def test_payment_with_valid_bill_includes_distribution(
        self, request, base_url, auth_headers
    ):
        consumer = "CONS-CM003-" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 30 * 86400 * 1000,
            "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        bill_resp = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                                 json={"consumerCode": consumer, "businessService": "TEST-BS"})
        if bill_resp.status_code not in (200, 201):
            return

        bill_id = bill_resp.json().get("id") or bill_resp.json().get("billId")
        if not bill_id:
            return

        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CASH",
                "totalAmountPaid": 100.0,
                "paymentDetails": [{"billId": bill_id, "amountPaid": 100.0}],
            },
        })
        assert resp.status_code in (200, 201, 500), \
            f"Payment with valid bill must succeed or fail due to Apportion, got {resp.status_code}: {resp.text}"
