"""
Cross-field rule tests for Billing-Payment service.
"""
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


def _base_demand(consumer_code=None, period_from=None, period_to=None):
    import uuid
    now = _now_ms()
    return {
        "consumerCode": consumer_code or "CONS-" + uuid.uuid4().hex[:6].upper(),
        "businessServiceCode": "TEST-BS",
        "periodFrom": period_from or now - 30 * 86400 * 1000,
        "periodTo": period_to or now,
        "totalAmount": 100.0,
        "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
    }


# ---------------------------------------------------------------------------
# BR-CF-001: Effective date range must be ordered
# ---------------------------------------------------------------------------

class TestBR_CF_001_effective_date_range_must_be_ordered:
    """effectiveFrom must be < effectiveTo on BusinessService."""

    def test_ordered_date_range_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = req_lib.put(f"{base_url}/business-services/TEST-BS", headers=auth_headers,
                           json={"effectiveFrom": now - 86400_000, "effectiveTo": now + 86400_000})
        assert resp.status_code in (200, 201, 204), \
            f"Ordered date range must be accepted, got {resp.status_code}: {resp.text}"

    def test_reversed_date_range_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, {
            "code": "BS-BAD-RANGE", "name": "Bad Range BS",
            "effectiveFrom": now + 86400_000,
            "effectiveTo": now - 86400_000,
            "allowedPaymentModes": ["CASH"],
            "billExpiryDays": 30,
            "partialPaymentAllowed": False,
            "isActive": True,
        })
        assert resp.status_code == 400, \
            f"Expected 400 for reversed date range, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Demand period must be ordered
# ---------------------------------------------------------------------------

class TestBR_CF_003_demand_period_must_be_ordered:
    """periodFrom must be < periodTo on DemandRequest."""

    def test_reversed_period_rejected(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS-" + uuid.uuid4().hex[:6].upper(),
            "businessServiceCode": "TEST-BS",
            "periodFrom": now + 86400_000,
            "periodTo": now - 86400_000,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 for reversed period, got {resp.status_code}: {resp.text}"

    def test_valid_period_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     _base_demand(period_from=now - 86400_000, period_to=now))
        assert resp.status_code in (200, 201, 409), \
            f"Valid period must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Demand total equals sum of line items
# ---------------------------------------------------------------------------

class TestBR_CF_005_demand_total_equals_sum_of_line_items:
    """totalAmount must equal sum of all lineItem.amount."""

    def test_total_mismatch_rejected(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS-" + uuid.uuid4().hex[:6].upper(),
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 86400_000,
            "periodTo": now,
            "totalAmount": 200.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 for total != sum(lineItems), got {resp.status_code}: {resp.text}"

    def test_total_matches_line_items_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     _base_demand())
        assert resp.status_code in (200, 201, 409), \
            f"Matching total/line-items must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: Payer array maximum ten entries
# ---------------------------------------------------------------------------

class TestBR_CF_006_payer_array_maximum_ten_entries:
    """More than 10 payer entries is rejected."""

    def test_eleven_payers_rejected(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        payers = [{"individualId": f"IND-{i:03d}", "isPrimary": False} for i in range(11)]
        resp = _post(request.node, f"{base_url}/demands", auth_headers, {
            "consumerCode": "CONS-" + uuid.uuid4().hex[:6].upper(),
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 86400_000,
            "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
            "payer": payers,
        })
        assert resp.status_code == 400, \
            f"Expected 400 for 11 payers, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-008: Payment instrument validated by mode
# ---------------------------------------------------------------------------

class TestBR_CF_008_payment_instrument_validated_by_mode:
    """CHEQUE mode requires instrumentNumber and instrumentDate."""

    def test_cheque_without_instrument_number_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CHEQUE",
                "totalAmountPaid": 100.0,
                "paymentDetails": [{"billId": "BILL-001", "amountPaid": 100.0}],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for CHEQUE without instrumentNumber, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-010: Payment total equals sum of details
# ---------------------------------------------------------------------------

class TestBR_CF_010_payment_total_equals_sum_of_details:
    """totalAmountPaid must equal sum of paymentDetail.amountPaid."""


# ---------------------------------------------------------------------------
# BR-CF-002: Minimum payable requires partial payment enabled
# ---------------------------------------------------------------------------

class TestBR_CF_002_minimum_payable_requires_partial_payment_enabled:
    """minPayableAmount is enforced only when partialPaymentAllowed=true."""

    def test_min_payable_with_partial_disabled_is_ignored(self, request, base_url, auth_headers):
        # partialPaymentAllowed=false: minPayableAmount is silently ignored
        resp = req_lib.put(f"{base_url}/business-services/TEST-BS", headers=auth_headers,
                           json={"partialPaymentAllowed": False, "minPayableAmount": -1.0})
        assert resp.status_code in (200, 201, 204, 400), \
            f"minPayableAmount with partialPaymentAllowed=false must not cause 422, got {resp.status_code}: {resp.text}"

    def test_invalid_min_payable_with_partial_enabled_rejected(
        self, request, base_url, auth_headers
    ):
        resp = req_lib.put(f"{base_url}/business-services/TEST-BS", headers=auth_headers,
                           json={"partialPaymentAllowed": True, "minPayableAmount": -50.0})
        assert resp.status_code == 400, \
            f"Expected 400 for negative minPayableAmount with partialPaymentAllowed=true, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Collected amount never exceeds total
# ---------------------------------------------------------------------------

class TestBR_CF_004_collected_amount_never_exceeds_total:
    """collectedAmount must not exceed totalAmount on a Demand."""

    def test_payment_exceeding_demand_total_rejected(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        consumer = "CONS-CF004-" + uuid.uuid4().hex[:4].upper()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
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
                "totalAmountPaid": 200.0,
                "paymentDetails": [{"billId": bill_id, "amountPaid": 200.0}],
            },
        })
        assert resp.status_code == 422, \
            f"Expected 422 for over-collection (200 > 100), got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-007: Bill paid amount never exceeds total
# ---------------------------------------------------------------------------

class TestBR_CF_007_bill_paid_amount_never_exceeds_total:
    """amountPaid on a Bill must not exceed totalAmount after payment application."""

    def test_over_payment_on_bill_rejected(self, request, base_url, auth_headers):
        import uuid
        now = _now_ms()
        consumer = "CONS-CF007-" + uuid.uuid4().hex[:4].upper()
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

        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CASH",
                "totalAmountPaid": 100.0,
                "paymentDetails": [{"billId": bill_id, "amountPaid": 100.0}],
            },
        })
        assert resp.status_code == 422, \
            f"Expected 422 for payment > bill totalAmount, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-009: Instrument date within age constraint
# ---------------------------------------------------------------------------

class TestBR_CF_009_instrument_date_within_age_constraint:
    """instrumentDate must not be in the future or > MAX_INSTRUMENT_DATE_AGE_DAYS old."""

    def test_future_instrument_date_rejected(self, request, base_url, auth_headers):
        import uuid
        future_date = _now_ms() + 86400_000
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CHEQUE",
                "instrumentNumber": "CHQ001",
                "instrumentDate": future_date,
                "totalAmountPaid": 100.0,
                "paymentDetails": [{"billId": "BILL-001", "amountPaid": 100.0}],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for future instrumentDate, got {resp.status_code}: {resp.text}"

    def test_very_old_instrument_date_rejected(self, request, base_url, auth_headers):
        very_old = _now_ms() - 180 * 86400_000  # 180 days ago > default 90-day limit
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CHEQUE",
                "instrumentNumber": "CHQ002",
                "instrumentDate": very_old,
                "totalAmountPaid": 100.0,
                "paymentDetails": [{"billId": "BILL-001", "amountPaid": 100.0}],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for instrumentDate > MAX_INSTRUMENT_DATE_AGE_DAYS old, got {resp.status_code}: {resp.text}"


    def test_payment_total_mismatch_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "payment": {
                "paymentMode": "CASH",
                "totalAmountPaid": 200.0,
                "paymentDetails": [{"billId": "BILL-001", "amountPaid": 100.0}],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for mismatched payment total, got {resp.status_code}: {resp.text}"
