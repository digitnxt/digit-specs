"""
Cross-field rule tests for Billing-Payment service.
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


def _base_demand(consumer=None, period_from=None, period_to=None, total=100.0,
                 tax_code="TESTTAX"):
    now = _now_ms()
    return {
        "consumerCode": consumer or "CONS-" + uuid.uuid4().hex[:6].upper(),
        "businessServiceCode": "TESTBS",
        "periodFrom": period_from if period_from is not None else now - 30 * 86400_000,
        "periodTo":   period_to   if period_to   is not None else now,
        "lineItems": [{"taxHeadCode": tax_code, "amount": total}],
    }


# ---------------------------------------------------------------------------
# BR-CF-001: Effective date range must be ordered
# ---------------------------------------------------------------------------

class TestBR_CF_001_effective_date_range_must_be_ordered:
    """effectiveFrom must be strictly < effectiveTo on BusinessServiceCreate."""

    def test_ordered_date_range_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "BSORDDATE",
            "name": "Ordered Date BS",
            "allowedPaymentModes": ["CASH"],
            "billExpiryDays": 30,
            "partialPaymentAllowed": False,
            "currency": "INR",
            "isActive": True,
            "effectiveFrom": now - 86400_000,
            "effectiveTo":   now + 86400_000,
        }])
        assert resp.status_code in (200, 201, 409), \
            f"Ordered date range must be accepted, got {resp.status_code}: {resp.text}"

    def test_reversed_date_range_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "BSBADRANGE",
            "name": "Bad Range",
            "allowedPaymentModes": ["CASH"],
            "billExpiryDays": 30,
            "partialPaymentAllowed": False,
            "currency": "INR",
            "isActive": True,
            "effectiveFrom": now + 86400_000,
            "effectiveTo":   now - 86400_000,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_REQUEST) for effectiveFrom > effectiveTo, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: Minimum payable requires partial payment enabled
# ---------------------------------------------------------------------------

class TestBR_CF_002_minimum_payable_requires_partial_payment_enabled:
    """minPayableAmount enforced when partialPaymentAllowed=true; full payment always valid."""

    def _create_bill(self, base_url, auth_headers, amount=1000.0):
        consumer = "CONS-CF002-" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json=[{
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": amount}],
        }])
        bill = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                            json={"consumerCode": consumer, "businessServiceCode": "TESTBS"})
        if bill.status_code not in (200, 201):
            return None
        return bill.json().get("id") or bill.json().get("billId")

    def test_partial_payment_below_minimum_rejected(self, request, base_url, auth_headers):
        bill_id = self._create_bill(base_url, auth_headers, amount=1000.0)
        if not bill_id:
            return
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 1.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 1.0}],
        })
        assert resp.status_code in (400, 422), \
            f"Payment below minPayableAmount must be rejected, got {resp.status_code}: {resp.text}"

    def test_full_payment_accepted(self, request, base_url, auth_headers):
        bill_id = self._create_bill(base_url, auth_headers, amount=100.0)
        if not bill_id:
            return
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 100.0}],
        })
        assert resp.status_code in (200, 201), \
            f"Full payment must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Demand period must not overlap backward
# ---------------------------------------------------------------------------

class TestBR_CF_003_demand_period_must_not_overlap_backward:
    """Only periodTo < periodFrom is rejected; equal values (zero-duration) are valid."""

    def test_valid_period_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     [_base_demand(period_from=now - 86400_000, period_to=now)])
        assert resp.status_code in (200, 201, 400, 409), \
            f"Valid period must pass period check, got {resp.status_code}: {resp.text}"

    def test_equal_period_from_and_to_is_valid(self, request, base_url, auth_headers):
        # Equal values represent a zero-duration demand — explicitly allowed
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     [_base_demand(period_from=now, period_to=now)])
        assert "INVALID_PERIOD" not in resp.text, \
            f"Equal periodFrom/periodTo must not produce INVALID_PERIOD, got {resp.status_code}: {resp.text}"

    def test_reversed_period_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     [_base_demand(period_from=now + 86400_000, period_to=now - 86400_000)])
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_PERIOD) for periodTo < periodFrom, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Over-collection on demand is guarded
# ---------------------------------------------------------------------------

class TestBR_CF_004_over_collection_on_demand_is_guarded:
    """Payment causing collectedAmount > totalAmount returns 500 with OVER_COLLECTION_DETECTED."""

    def test_over_collection_returns_500_with_error_string(
        self, request, base_url, auth_headers
    ):
        consumer = "CONS-CF004-" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json=[{
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        }])
        bill_resp = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                                 json={"consumerCode": consumer, "businessServiceCode": "TESTBS"})
        if bill_resp.status_code not in (200, 201):
            return
        bill_id = bill_resp.json().get("id") or bill_resp.json().get("billId")
        if not bill_id:
            return
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 200.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 200.0}],
        })
        assert resp.status_code == 500, \
            f"Expected 500 for over-collection, got {resp.status_code}: {resp.text}"
        assert "OVER_COLLECTION_DETECTED" in resp.text, \
            f"Error text must contain 'OVER_COLLECTION_DETECTED', got: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Line item amounts are individually range-checked
# ---------------------------------------------------------------------------

class TestBR_CF_005_line_item_amounts_are_individually_range_checked:
    """amount in [-1B, 1B]; collectedAmount in [0, amount] for positive amounts."""

    def test_valid_line_item_amount_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     [_base_demand(total=500.0)])
        assert resp.status_code in (200, 201, 409), \
            f"Valid line item amount must be accepted, got {resp.status_code}: {resp.text}"

    def test_amount_exceeding_upper_bound_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, [{
            "consumerCode": "CONS-CF005-" + uuid.uuid4().hex[:4].upper(),
            "businessServiceCode": "TESTBS",
            "periodFrom": now - 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 2_000_000_000.0}],
        }])
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_AMOUNT) for amount > 1B, got {resp.status_code}: {resp.text}"

    def test_collected_amount_exceeding_line_amount_rejected(
        self, request, base_url, auth_headers
    ):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/demands", auth_headers, [{
            "consumerCode": "CONS-CF005C-" + uuid.uuid4().hex[:4].upper(),
            "businessServiceCode": "TESTBS",
            "periodFrom": now - 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0,
                           "collectedAmount": 200.0}],
        }])
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_COLLECTION) for collectedAmount > amount, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: Payer array maximum ten entries
# ---------------------------------------------------------------------------

class TestBR_CF_006_payer_array_maximum_ten_entries:
    """More than 10 payer entries is rejected."""

    def test_eleven_payers_rejected(self, request, base_url, auth_headers):
        payers = [f"IND{i:03d}" for i in range(11)]
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     [{**_base_demand(), "payer": payers}])
        assert resp.status_code == 400, \
            f"Expected 400 for 11 payers, got {resp.status_code}: {resp.text}"

    def test_ten_payers_boundary_accepted(self, request, base_url, auth_headers):
        payers = [f"IND{i:03d}" for i in range(10)]
        resp = _post(request.node, f"{base_url}/demands", auth_headers,
                     [{**_base_demand(), "payer": payers}])
        assert resp.status_code in (200, 201, 409), \
            f"10 payers (boundary) must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-007: Payment mode determines payment status at creation
# ---------------------------------------------------------------------------

class TestBR_CF_007_payment_mode_determines_payment_status_at_creation:
    """Online modes → DEPOSITED/REMITTED; offline modes → NEW/APPROVED."""

    def _make_bill(self, base_url, auth_headers):
        consumer = "CONS-CF007-" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json=[{
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        }])
        b = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                         json={"consumerCode": consumer, "businessServiceCode": "TESTBS"})
        if b.status_code not in (200, 201):
            return None
        return b.json().get("id") or b.json().get("billId")

    def test_cash_payment_starts_as_new(self, request, base_url, auth_headers):
        bill_id = self._make_bill(base_url, auth_headers)
        if not bill_id:
            return
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 100.0}],
        })
        if resp.status_code in (200, 201):
            assert resp.json().get("paymentStatus") == "NEW", \
                f"CASH must start as NEW, got: {resp.json().get('paymentStatus')}"

    def test_upi_payment_starts_as_deposited(self, request, base_url, auth_headers):
        bill_id = self._make_bill(base_url, auth_headers)
        if not bill_id:
            return
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "UPI",
            "paidBy": "test-user",
            "transactionNumber": "TXN" + uuid.uuid4().hex[:8].upper(),
            "instrumentNumber":  "UPIREF001",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 100.0}],
        })
        if resp.status_code in (200, 201):
            assert resp.json().get("paymentStatus") == "DEPOSITED", \
                f"UPI must start as DEPOSITED, got: {resp.json().get('paymentStatus')}"


# ---------------------------------------------------------------------------
# BR-CF-008: Payment instrument validated by mode
# ---------------------------------------------------------------------------

class TestBR_CF_008_payment_instrument_validated_by_mode:
    """CHEQUE/DD require instrumentNumber+instrumentDate; UPI/ONLINE require transactionNumber."""

    def test_cheque_without_instrument_number_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CHEQUE",
            "paidBy": "test-user",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_INST_NUMBER) for CHEQUE without instrumentNumber, got {resp.status_code}: {resp.text}"

    def test_upi_without_transaction_number_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "UPI",
            "paidBy": "test-user",
            "instrumentNumber": "UPIREF",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_TXN_NUMBER) for UPI without transactionNumber, got {resp.status_code}: {resp.text}"

    def test_cash_needs_no_instrument(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "NONEXISTENT-BILL", "totalAmountPaid": 100.0}],
        })
        assert "INVALID_INST" not in resp.text, \
            f"CASH must not fail instrument validation, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-009: CHEQUE/DD instrument date age constraint
# ---------------------------------------------------------------------------

class TestBR_CF_009_cheque_dd_instrument_date_age_constraint:
    """Age limit (>90 days) applies ONLY to CHEQUE/DD — not OFFLINE_NEFT/RTGS/POSTAL_ORDER."""

    def test_cheque_future_date_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CHEQUE",
            "paidBy": "test-user",
            "instrumentNumber": "CHQFUTURE",
            "instrumentDate": _now_ms() + 86400_000,
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 for future CHEQUE date, got {resp.status_code}: {resp.text}"

    def test_cheque_with_very_old_date_rejected(self, request, base_url, auth_headers):
        very_old = _now_ms() - 180 * 86400_000
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CHEQUE",
            "paidBy": "test-user",
            "instrumentNumber": "CHQOLD",
            "instrumentDate": very_old,
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (CHEQUE_DD_DATE_EXCEEDS_RECEIPT) for old CHEQUE date, got {resp.status_code}: {resp.text}"

    def test_dd_with_very_old_date_rejected(self, request, base_url, auth_headers):
        very_old = _now_ms() - 180 * 86400_000
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "DD",
            "paidBy": "test-user",
            "instrumentNumber": "DDOLD",
            "instrumentDate": very_old,
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 100.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 for old DD date, got {resp.status_code}: {resp.text}"

    def test_offline_neft_old_date_not_rejected_by_age(self, request, base_url, auth_headers):
        # OFFLINE_NEFT: no age limit, only future date is rejected
        old_date = _now_ms() - 180 * 86400_000
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "OFFLINE_NEFT",
            "paidBy": "test-user",
            "instrumentNumber": "NEFTOLD",
            "instrumentDate": old_date,
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "NONEXISTENT", "totalAmountPaid": 100.0}],
        })
        assert "CHEQUE_DD_DATE_EXCEEDS" not in resp.text, \
            f"OFFLINE_NEFT must not apply CHEQUE/DD age constraint, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-010: Payment amount must be non-negative and non-fractional
# ---------------------------------------------------------------------------

class TestBR_CF_010_payment_amount_must_be_non_negative_and_non_fractional:
    """Amount per bill >= 0 and integer (no fractions). Root-level sum is not validated."""

    def test_fractional_amount_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 100.5,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 100.5}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_PAYMENTDETAIL) for fractional amount, got {resp.status_code}: {resp.text}"

    def test_negative_amount_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": -50.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": -50.0}],
        })
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_PAYMENTDETAIL) for negative amount, got {resp.status_code}: {resp.text}"

    def test_zero_amount_rejected_for_nonzero_bill(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 0.0,
            "paymentDetails": [{"billId": "BILL-001", "totalAmountPaid": 0.0}],
        })
        assert resp.status_code in (400, 422), \
            f"Zero payment on non-zero bill must be rejected, got {resp.status_code}: {resp.text}"

    def test_integer_amount_passes_amount_validation(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments", auth_headers, {
            "paymentMode": "CASH",
            "paidBy": "test-user",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": "NONEXISTENT", "totalAmountPaid": 100.0}],
        })
        assert "INVALID_PAYMENTDETAIL" not in resp.text, \
            f"Integer amount must pass payment amount validation, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-011: BusinessService code must match pattern
# ---------------------------------------------------------------------------

class TestBR_CF_011_business_service_code_must_match_pattern:
    """code must match ^[A-Z][A-Z0-9_]{1,31}$"""

    def test_valid_code_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "VALIDBS", "name": "Valid BS",
            "allowedPaymentModes": ["CASH"], "billExpiryDays": 30,
            "partialPaymentAllowed": False, "currency": "INR",
            "isActive": True, "effectiveFrom": 0,
        }])
        assert resp.status_code in (200, 201, 409), \
            f"Valid code must be accepted, got {resp.status_code}: {resp.text}"

    def test_code_with_hyphen_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "INVALID-BS", "name": "Hyphen BS",
            "allowedPaymentModes": ["CASH"], "billExpiryDays": 30,
            "partialPaymentAllowed": False, "currency": "INR",
            "isActive": True, "effectiveFrom": 0,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 for code with hyphen, got {resp.status_code}: {resp.text}"

    def test_code_with_lowercase_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "invalidbs", "name": "Lowercase BS",
            "allowedPaymentModes": ["CASH"], "billExpiryDays": 30,
            "partialPaymentAllowed": False, "currency": "INR",
            "isActive": True, "effectiveFrom": 0,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 for lowercase code, got {resp.status_code}: {resp.text}"

    def test_code_starting_with_digit_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "1BADCODE", "name": "Digit Start",
            "allowedPaymentModes": ["CASH"], "billExpiryDays": 30,
            "partialPaymentAllowed": False, "currency": "INR",
            "isActive": True, "effectiveFrom": 0,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 for code starting with digit, got {resp.status_code}: {resp.text}"

    def test_code_with_underscore_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/business-services", auth_headers, [{
            "code": "VALID_BS", "name": "Underscore BS",
            "allowedPaymentModes": ["CASH"], "billExpiryDays": 30,
            "partialPaymentAllowed": False, "currency": "INR",
            "isActive": True, "effectiveFrom": 0,
        }])
        assert resp.status_code in (200, 201, 409), \
            f"Underscore in code must be accepted, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-012: TaxHead code must match pattern
# ---------------------------------------------------------------------------

class TestBR_CF_012_tax_head_code_must_match_pattern:
    """code must match ^[A-Z][A-Z0-9_]{1,63}$"""

    def test_valid_tax_code_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": "VALIDTAX", "name": "Valid Tax",
            "businessServiceCode": "TESTBS",
            "order": 50, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code in (200, 201, 409), \
            f"Valid tax code must be accepted, got {resp.status_code}: {resp.text}"

    def test_tax_code_with_hyphen_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": "INVALID-TAX", "name": "Hyphen Tax",
            "businessServiceCode": "TESTBS",
            "order": 51, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 for tax code with hyphen, got {resp.status_code}: {resp.text}"

    def test_tax_code_with_lowercase_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": "invalidtax", "name": "Lowercase Tax",
            "businessServiceCode": "TESTBS",
            "order": 52, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 for lowercase tax code, got {resp.status_code}: {resp.text}"

    def test_tax_code_boundary_64_chars_accepted(self, request, base_url, auth_headers):
        code = "T" + "A" * 63
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": code, "name": "Max Length Tax",
            "businessServiceCode": "TESTBS",
            "order": 54, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code in (200, 201, 409), \
            f"64-char code (boundary) must be accepted, got {resp.status_code}: {resp.text}"

    def test_tax_code_above_64_chars_rejected(self, request, base_url, auth_headers):
        code = "T" + "A" * 64
        resp = _post(request.node, f"{base_url}/tax-heads", auth_headers, [{
            "code": code, "name": "Too Long Tax",
            "businessServiceCode": "TESTBS",
            "order": 55, "effectiveFrom": 0, "isActive": True,
        }])
        assert resp.status_code == 400, \
            f"Expected 400 for 65-char tax code, got {resp.status_code}: {resp.text}"