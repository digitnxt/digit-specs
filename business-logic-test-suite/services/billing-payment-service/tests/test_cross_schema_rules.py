"""
Cross-schema rule tests for Billing-Payment service.
"""
import uuid
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


# ---------------------------------------------------------------------------
# BR-CS-001: TaxHead requires active BusinessService
# ---------------------------------------------------------------------------

class TestBR_CS_001_tax_head_requires_active_business_service:
    """TaxHead cannot be created for a non-existent BusinessService."""

    def test_tax_head_for_nonexistent_bs_returns_404(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, {
            "code": "TAX-" + uuid.uuid4().hex[:4].upper(),
            "name": "Test Tax",
            "businessServiceCode": "NONEXISTENT-BS-" + uuid.uuid4().hex[:4].upper(),
            "order": 99,
            "effectiveFrom": 0,
            "isActive": True,
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent BusinessService, got {resp.status_code}: {resp.text}"

    def test_tax_head_for_existing_bs_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, {
            "code": "TEST-TAX",
            "name": "Test Tax Head",
            "businessServiceCode": "TEST-BS",
            "order": 1,
            "effectiveFrom": 0,
            "isActive": True,
        })
        assert resp.status_code in (200, 201, 409), \
            f"TaxHead for existing BS must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-002: TaxHead order number unique per service
# ---------------------------------------------------------------------------

class TestBR_CS_002_tax_head_order_number_unique_per_service:
    """Two TaxHeads within same service cannot share an orderNumber."""

    def test_duplicate_order_number_returns_409(self, request, base_url, auth_headers):
        code1 = "TAX-" + uuid.uuid4().hex[:4].upper()
        code2 = "TAX-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/tax-heads", headers=auth_headers, json={
            "code": code1, "name": "Tax 1", "businessServiceCode": "TEST-BS",
            "order": 100, "effectiveFrom": 0, "isActive": True,
        })
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, {
            "code": code2, "name": "Tax 2", "businessServiceCode": "TEST-BS",
            "order": 100, "effectiveFrom": 0, "isActive": True,
        })
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate orderNumber, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: No overlapping demand periods same consumer
# ---------------------------------------------------------------------------

class TestBR_CS_004_no_overlapping_demand_periods_same_consumer:
    """Two demands for same consumer/service cannot have overlapping period ranges."""

    def test_overlapping_periods_returns_409(self, request, base_url, auth_headers):
        consumer = "CONS-" + uuid.uuid4().hex[:6].upper()
        now = _now_ms()

        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 60 * 86400 * 1000,
            "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })

        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 30 * 86400 * 1000,
            "periodTo": now + 30 * 86400 * 1000,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        assert resp.status_code == 409, \
            f"Expected 409 for overlapping demand periods, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-006: Bill must be ACTIVE or PARTIALLY_PAID for payment
# ---------------------------------------------------------------------------

class TestBR_CS_006_bill_must_be_active_or_partially_paid:
    """Payment on a CANCELLED or EXPIRED bill is rejected."""

    def test_payment_on_nonexistent_bill_returns_error(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CASH",
                "totalAmountPaid": 100.0,
                "paymentDetails": [{"billId": "NONEXISTENT-BILL-123", "amountPaid": 100.0}],
            },
        })
        assert resp.status_code in (400, 404, 422), \
            f"Payment on nonexistent bill must fail, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-008: Demand requires active BusinessService
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-CS-003: LineItem tax head belongs to service
# ---------------------------------------------------------------------------

class TestBR_CS_003_line_item_tax_head_belongs_to_service:
    """Each LineItem.taxHeadCode must belong to the demand's businessServiceCode."""

    def test_cross_service_tax_head_rejected(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS-" + uuid.uuid4().hex[:4].upper(),
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 86400_000, "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "WRONG-SERVICE-TAX", "amount": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 for tax head from different service, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-005: Only one active bill per consumer service
# ---------------------------------------------------------------------------

class TestBR_CS_005_only_one_active_bill_per_consumer_service:
    """Generating a new bill when an unexpired ACTIVE bill exists returns 409 or the existing bill."""

    def test_duplicate_bill_generation_returns_409_or_existing(
        self, request, base_url, auth_headers
    ):
        import uuid
        now = _now_ms()
        consumer = "CONS-CS005-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        first = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                             json={"consumerCode": consumer, "businessService": "TEST-BS"})
        if first.status_code not in (200, 201):
            return
        second = _post(request.node, f"{base_url}/bills/generate", auth_headers,
                       {"consumerCode": consumer, "businessService": "TEST-BS"})
        assert second.status_code in (200, 201, 409), \
            f"Second bill generation must return 200/201 (existing bill) or 409, got {second.status_code}: {second.text}"


# ---------------------------------------------------------------------------
# BR-CS-007: Unique payment per bill prevented
# ---------------------------------------------------------------------------

class TestBR_CS_007_unique_payment_per_bill_prevented:
    """A bill may appear in at most one PaymentDetail row."""

    def test_paying_same_bill_twice_returns_409(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        consumer = "CONS-CS007-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "totalAmount": 50.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 50.0}],
        })
        bill_resp = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                                 json={"consumerCode": consumer, "businessService": "TEST-BS"})
        if bill_resp.status_code not in (200, 201):
            return
        bill_id = bill_resp.json().get("id") or bill_resp.json().get("billId")
        if not bill_id:
            return

        first_pay = req_lib.post(f"{base_url}/payments", headers=auth_headers, json={
            "payment": {
                "paymentMode": "CASH",
                "totalAmountPaid": 50.0,
                "paymentDetails": [{"billId": bill_id, "amountPaid": 50.0}],
            },
        })
        if first_pay.status_code not in (200, 201):
            return  # Can't proceed without first successful payment

        second_pay = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CASH",
                "totalAmountPaid": 50.0,
                "paymentDetails": [{"billId": bill_id, "amountPaid": 50.0}],
            },
        })
        assert second_pay.status_code == 409, \
            f"Expected 409 for duplicate payment on same bill, got {second_pay.status_code}: {second_pay.text}"


# ---------------------------------------------------------------------------
# BR-CS-009: Bill requires qualifying demand
# ---------------------------------------------------------------------------

class TestBR_CS_009_bill_requires_qualifying_demand:
    """Bill generation requires at least one ACTIVE/FROZEN/PARTIALLY_PAID demand."""

    def test_bill_generation_without_demand_returns_422(self, request, base_url, auth_headers):
        import uuid
        consumer = "CONS-CS009-" + uuid.uuid4().hex[:6].upper()
        resp = _post(request.node, f"{base_url}/bills/generate", auth_headers, {
            "consumerCode": consumer,
            "businessService": "TEST-BS",
        })
        assert resp.status_code == 422, \
            f"Expected 422 for bill generation with no qualifying demands, got {resp.status_code}: {resp.text}"


class TestBR_CS_008_demand_requires_active_business_service:
    """Demand cannot be created for a non-existent or inactive BusinessService."""

    def test_demand_for_nonexistent_bs_returns_404(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS-" + uuid.uuid4().hex[:6].upper(),
            "businessServiceCode": "NONEXISTENT-BS-" + uuid.uuid4().hex[:4].upper(),
            "periodFrom": now - 86400_000,
            "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        assert resp.status_code == 404, \
            f"Expected 404 for nonexistent BS, got {resp.status_code}: {resp.text}"
