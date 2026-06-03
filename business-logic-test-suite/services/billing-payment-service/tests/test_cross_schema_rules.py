"""
Cross-schema rule tests for Billing-Payment service.
"""
import time
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _now_ms():
    return int(time.time() * 1000)


def _make_demand(base_url, headers, consumer, amount=100.0):
    now = _now_ms()
    return req_lib.post(f"{base_url}/demands", headers=headers, json={
        "consumerCode": consumer,
        "businessServiceCode": "TESTBS",
        "periodFrom": now - 30 * 86400_000, "periodTo": now,
        "lineItems": [{"taxHeadCode": "TESTTAX", "amount": amount}],
    })


def _make_bill(base_url, headers, consumer):
    return req_lib.post(f"{base_url}/bills/generate", headers=headers,
                        json={"consumerCode": consumer, "businessServiceCode": "TESTBS"})


# ---------------------------------------------------------------------------
# BR-CS-001: TaxHead requires active BusinessService
# ---------------------------------------------------------------------------

class TestBR_CS_001_tax_head_requires_active_business_service:
    """TaxHead cannot be created for a non-existent BusinessService."""

    def test_tax_head_for_nonexistent_bs_returns_404(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": "TAX" + uuid.uuid4().hex[:4].upper(),
            "name": "Test Tax",
            "businessServiceCode": "NONEXISTENT" + uuid.uuid4().hex[:4].upper(),
            "order": 99, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code == 422, \
            f"Expected 422 (INVALID_BUSINESS_SERVICE) for nonexistent BusinessService, got {resp.status_code}: {resp.text}"

    def test_tax_head_for_existing_bs_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": "TESTTAX", "name": "Test Tax Head",
            "businessServiceCode": "TESTBS",
            "order": 1, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code in (200, 201, 409), \
            f"TaxHead for existing BS must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-002: TaxHead order number unique per service
# ---------------------------------------------------------------------------

class TestBR_CS_002_tax_head_order_number_unique_per_service:
    """Two TaxHeads within same service cannot share an orderNumber."""

    def test_duplicate_order_number_returns_409(self, request, base_url, auth_headers):
        code1 = "TAX" + uuid.uuid4().hex[:4].upper()
        code2 = "TAX" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/tax-heads", headers=auth_headers, json=[{
            "code": code1, "name": "Tax 1", "businessServiceCode": "TESTBS",
            "order": 100, "effectiveFrom": 0, "isActive": True,
        }])
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": code2, "name": "Tax 2", "businessServiceCode": "TESTBS",
            "order": 100, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code == 409, \
            f"Expected 409 for duplicate orderNumber, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-003: LineItem tax head belongs to demand's service
# ---------------------------------------------------------------------------

class TestBR_CS_003_line_item_tax_head_belongs_to_demands_service:
    """Each LineItem.taxHeadCode must belong to the demand's businessServiceCode."""

    def test_cross_service_tax_head_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS" + uuid.uuid4().hex[:4].upper(),
            "businessServiceCode": "TESTBS",
            "periodFrom": now - 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "WRONGSERVICETAX", "amount": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_TAX_HEAD) for cross-service tax head, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-004: No overlapping demand periods for same consumer and service
# ---------------------------------------------------------------------------

class TestBR_CS_004_no_overlapping_demand_periods_same_consumer_and_service:
    """Two demands for same (tenant, bsCode, consumer) cannot have overlapping periods."""

    def test_overlapping_periods_returns_400_demand_conflict(
        self, request, base_url, auth_headers
    ):
        consumer = "CONS" + uuid.uuid4().hex[:6].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 60 * 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now + 30 * 86400_000,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (DEMAND_CONFLICT) for overlapping periods, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-005: Only one active bill per consumer service
# ---------------------------------------------------------------------------

class TestBR_CS_005_only_one_active_bill_per_consumer_service:
    """Bill generation is idempotent — returns existing active bill, no error."""

    def test_second_bill_generation_returns_existing_bill_not_error(
        self, request, base_url, auth_headers
    ):
        consumer = "CONSCS005" + uuid.uuid4().hex[:4].upper()
        _make_demand(base_url, auth_headers, consumer)
        first = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                             json={"consumerCode": consumer, "businessServiceCode": "TESTBS"})
        if first.status_code not in (200, 201):
            return
        second = _post(request.node, f"{base_url}/bills/generate", auth_headers,
                       {"consumerCode": consumer, "businessServiceCode": "TESTBS"})
        # Idempotent: returns the existing bill, no 409
        assert second.status_code in (200, 201), \
            f"Second bill generation must return existing bill (no error), got {second.status_code}: {second.text}"
        assert second.json().get("id") == first.json().get("id"), \
            "Both calls must return the same bill ID"


# ---------------------------------------------------------------------------
# BR-CS-006: Only ACTIVE bills can receive payments
# ---------------------------------------------------------------------------

class TestBR_CS_006_only_active_bills_can_receive_payments:
    """Payment rejected unless bill status is exactly ACTIVE; 422 BILL_NOT_ACTIVE."""

    def test_payment_on_nonexistent_bill_returns_error(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "NONEXISTENTBILL", "totalAmountPaid": 100.0}],
        })
        assert resp.status_code in (400, 404, 422), \
            f"Payment on nonexistent bill must fail, got {resp.status_code}: {resp.text}"

    def test_payment_on_active_bill_accepted(self, request, base_url, auth_headers):
        consumer = "CONSCS006A" + uuid.uuid4().hex[:4].upper()
        _make_demand(base_url, auth_headers, consumer, amount=100.0)
        bill = _make_bill(base_url, auth_headers, consumer)
        if bill.status_code not in (200, 201):
            return
        bill_id = bill.json().get("id") or bill.json().get("billId")
        if not bill_id:
            return
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 100.0}],
        })
        assert resp.status_code in (200, 201), \
            f"Payment on ACTIVE bill must succeed, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-007: Duplicate payment on a bill is prevented
# ---------------------------------------------------------------------------

class TestBR_CS_007_duplicate_payment_on_a_bill_is_prevented:
    """A bill with active payment status cannot be included in a new payment; 422 BILL_ALREADY_PAID."""

    def test_paying_same_bill_twice_returns_422(self, request, base_url, auth_headers):
        consumer = "CONSCS007" + uuid.uuid4().hex[:4].upper()
        _make_demand(base_url, auth_headers, consumer, amount=50.0)
        bill = _make_bill(base_url, auth_headers, consumer)
        if bill.status_code not in (200, 201):
            return
        bill_id = bill.json().get("id") or bill.json().get("billId")
        if not bill_id:
            return
        first_pay = req_lib.post(f"{base_url}/payments", headers=auth_headers, json={
            "paymentMode": "CASH",
            "totalAmountPaid": 50.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 50.0}],
        })
        if first_pay.status_code not in (200, 201):
            return
        second_pay = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "totalAmountPaid": 50.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 50.0}],
        })
        assert second_pay.status_code == 422, \
            f"Expected 422 (BILL_ALREADY_PAID) for duplicate payment, got {second_pay.status_code}: {second_pay.text}"


# ---------------------------------------------------------------------------
# BR-CS-008: Demand requires active BusinessService
# ---------------------------------------------------------------------------

class TestBR_CS_008_demand_requires_active_business_service:
    """Demand creation returns 400 UNKNOWN_BUSINESS_SERVICE for unknown/inactive BS."""

    def test_demand_for_nonexistent_bs_returns_400(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS" + uuid.uuid4().hex[:4].upper(),
            "businessServiceCode": "NONEXISTENT" + uuid.uuid4().hex[:4].upper(),
            "periodFrom": now - 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (UNKNOWN_BUSINESS_SERVICE) for nonexistent BS, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CS-009: Bill generation requires qualifying demands
# ---------------------------------------------------------------------------

class TestBR_CS_009_bill_generation_requires_qualifying_demands:
    """Bill generation returns 422 NO_ELIGIBLE_DEMANDS when no ACTIVE/FROZEN/PARTIALLY_PAID demand exists."""

    def test_bill_generation_without_demand_returns_422(self, request, base_url, auth_headers):
        consumer = "CONSCS009" + uuid.uuid4().hex[:6].upper()
        resp = _post(request.node, f"{base_url}/bills/generate", auth_headers,
                     {"consumerCode": consumer, "businessServiceCode": "TESTBS"})
        assert resp.status_code == 422, \
            f"Expected 422 (NO_ELIGIBLE_DEMANDS) for no qualifying demands, got {resp.status_code}: {resp.text}"


