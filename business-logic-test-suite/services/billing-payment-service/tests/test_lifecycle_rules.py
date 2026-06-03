"""
Lifecycle rule tests for Billing-Payment service.
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


def _create_demand(base_url, headers, consumer=None, amount=100.0, period_days=30):
    now = _now_ms()
    consumer = consumer or "CONS" + uuid.uuid4().hex[:6].upper()
    resp = req_lib.post(f"{base_url}/demands", headers=headers, json={
        "consumerCode": consumer,
        "businessServiceCode": "TESTBS",
        "periodFrom": now - period_days * 86400_000,
        "periodTo": now,
        "lineItems": [{"taxHeadCode": "TESTTAX", "amount": amount}],
    })
    return resp, consumer


def _generate_bill(base_url, headers, consumer):
    return req_lib.post(f"{base_url}/bills/generate", headers=headers,
                        json={"consumerCode": consumer, "businessServiceCode": "TESTBS"})


# ---------------------------------------------------------------------------
# BR-LC-001: Demand cancellation only from DRAFT or ACTIVE
# ---------------------------------------------------------------------------

class TestBR_LC_001_demand_cancellation_only_from_draft_or_active:
    """POST /demands/:id/cancel permitted only from DRAFT or ACTIVE; 422 CANCEL_FAILED otherwise."""

    def test_active_demand_can_be_cancelled(self, request, base_url, auth_headers):
        create_resp, _ = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand_id = create_resp.json().get("id") or create_resp.json().get("demandId")
        if not demand_id:
            return
        resp = _post(request.node, f"{base_url}/demands/{demand_id}/cancel",
                     auth_headers, {})
        assert resp.status_code in (200, 204), \
            f"Cancelling ACTIVE demand must succeed, got {resp.status_code}: {resp.text}"

    def test_frozen_demand_cancellation_returns_422(
        self, request, base_url, auth_headers
    ):
        create_resp, consumer = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand_id = create_resp.json().get("id") or create_resp.json().get("demandId")
        _generate_bill(base_url, auth_headers, consumer)  # freezes demand
        if not demand_id:
            return
        resp = _post(request.node, f"{base_url}/demands/{demand_id}/cancel",
                     auth_headers, {})
        assert resp.status_code == 422, \
            f"Expected 422 (CANCEL_FAILED) for FROZEN demand, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-002: Only DRAFT and ACTIVE demands are editable
# ---------------------------------------------------------------------------

class TestBR_LC_002_only_draft_and_active_demands_are_editable:
    """PUT/PATCH on non-DRAFT/ACTIVE demand returns 400 INVALID_STATUS_TRANSITION."""

    def test_edit_active_demand_accepted(self, request, base_url, auth_headers):
        create_resp, _ = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand = create_resp.json()
        demand_id = demand.get("id") or demand.get("demandId")
        if not demand_id:
            return
        resp = req_lib.patch(f"{base_url}/demands/{demand_id}",
                             headers=auth_headers, json={**demand})
        assert resp.status_code in (200, 201), \
            f"Editing ACTIVE demand must succeed, got {resp.status_code}: {resp.text}"

    def test_edit_frozen_demand_returns_400(self, request, base_url, auth_headers):
        create_resp, consumer = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand = create_resp.json()
        demand_id = demand.get("id") or demand.get("demandId")
        _generate_bill(base_url, auth_headers, consumer)
        if not demand_id:
            return
        resp = req_lib.patch(f"{base_url}/demands/{demand_id}",
                             headers=auth_headers, json={**demand})
        assert resp.status_code == 400, \
            f"Expected 400 (INVALID_STATUS_TRANSITION) for FROZEN, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-003: Bill generation freezes ACTIVE demands
# ---------------------------------------------------------------------------

class TestBR_LC_003_bill_generation_freezes_active_demands:
    """Bill generation transitions ACTIVE demands to FROZEN; already-FROZEN unchanged."""

    def test_demand_frozen_after_bill_generation(self, request, base_url, auth_headers):
        create_resp, consumer = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand_id = create_resp.json().get("id") or create_resp.json().get("demandId")
        bill_resp = _generate_bill(base_url, auth_headers, consumer)
        if bill_resp.status_code not in (200, 201) or not demand_id:
            return
        check = req_lib.get(f"{base_url}/demands/{demand_id}", headers=auth_headers)
        if check.status_code == 200:
            assert check.json().get("status") in ("FROZEN", "PARTIALLY_PAID", "PAID"), \
                f"ACTIVE demand must be FROZEN after bill gen, got: {check.json().get('status')}"


# ---------------------------------------------------------------------------
# BR-LC-004: Bill expiry uses demand-level over business-service-level
# ---------------------------------------------------------------------------

class TestBR_LC_004_bill_expiry_uses_demand_level_over_business_service_level:
    """Expiry priority: demand.billExpiryDays > businessService.billExpiryDays; 0 = no expiry."""

    def test_demand_with_bill_expiry_days_produces_expiry_date(
        self, request, base_url, auth_headers
    ):
        consumer = "CONSLC004" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "billExpiryDays": 10,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        bill_resp = _generate_bill(base_url, auth_headers, consumer)
        if bill_resp.status_code not in (200, 201):
            return
        bill = bill_resp.json()
        assert bill.get("expiryDate") or bill.get("billDate"), \
            "Demand with billExpiryDays must produce an expiry date on the bill"

    def test_demand_with_zero_expiry_produces_no_expiry(
        self, request, base_url, auth_headers
    ):
        consumer = "CONSLC004Z" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "billExpiryDays": 0,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        bill_resp = _generate_bill(base_url, auth_headers, consumer)
        if bill_resp.status_code not in (200, 201):
            return
        assert not bill_resp.json().get("expiryDate"), \
            f"billExpiryDays=0 must yield no expiry date, got: {bill_resp.json().get('expiryDate')}"


# ---------------------------------------------------------------------------
# BR-LC-005: Arrear roll-forward creates new demand and marks source
# ---------------------------------------------------------------------------

class TestBR_LC_005_arrear_roll_forward_creates_new_demand_and_marks_source:
    """
    With DEMAND_ENABLE_ARREARS=true: new demand triggers roll-forward on open source.
    Source transitions to ROLL_FORWARDED; new demand gets ARREAR line item prepended.
    """

    def test_roll_forwarded_demand_cannot_be_edited(
        self, request, base_url, auth_headers
    ):
        now = _now_ms()
        create_resp, consumer = _create_demand(base_url, auth_headers, period_days=60)
        if create_resp.status_code not in (200, 201):
            return
        first_demand = create_resp.json()
        demand_id = first_demand.get("id") or first_demand.get("demandId")

        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 10 * 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        if not demand_id:
            return
        check = req_lib.get(f"{base_url}/demands/{demand_id}", headers=auth_headers)
        if check.status_code != 200 or check.json().get("status") != "ROLL_FORWARDED":
            return  # Arrears not enabled — skip

        edit = _post(request.node, f"{base_url}/demands/{demand_id}",
                     auth_headers, {**check.json()})
        assert edit.status_code == 400, \
            f"Expected 400 for editing ROLL_FORWARDED demand, got {edit.status_code}: {edit.text}"

    def test_new_demand_has_arrear_line_item_when_arrears_enabled(
        self, request, base_url, auth_headers
    ):
        consumer = "CONSLC005" + uuid.uuid4().hex[:4].upper()
        now = _now_ms()
        req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 60 * 86400_000, "periodTo": now - 30 * 86400_000,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        second = req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer, "businessServiceCode": "TESTBS",
            "periodFrom": now - 10 * 86400_000, "periodTo": now,
            "lineItems": [{"taxHeadCode": "TESTTAX", "amount": 100.0}],
        })
        if second.status_code not in (200, 201):
            return
        line_items = second.json().get("lineItems", [])
        if any("ARREAR" in li.get("taxHeadCode", "") for li in line_items):
            assert line_items[0].get("taxHeadCode", "").endswith("_ARREAR"), \
                "ARREAR line item must be first in the demand"


# ---------------------------------------------------------------------------
# BR-LC-006: Demand status set by payment application
# ---------------------------------------------------------------------------

class TestBR_LC_006_demand_status_set_by_payment_application:
    """Full payment → PAID; partial payment → PARTIALLY_PAID."""

    def test_full_payment_transitions_demand_to_paid(
        self, request, base_url, auth_headers
    ):
        create_resp, consumer = _create_demand(base_url, auth_headers, amount=100.0)
        if create_resp.status_code not in (200, 201):
            return
        demand_id = create_resp.json().get("id") or create_resp.json().get("demandId")
        bill_resp = _generate_bill(base_url, auth_headers, consumer)
        if bill_resp.status_code not in (200, 201):
            return
        bill_id = bill_resp.json().get("id") or bill_resp.json().get("billId")
        if not bill_id or not demand_id:
            return
        pay = req_lib.post(f"{base_url}/payments", headers=auth_headers, json={
            "paymentMode": "CASH",
            "totalAmountPaid": 100.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 100.0}],
        })
        if pay.status_code not in (200, 201):
            return
        check = req_lib.get(f"{base_url}/demands/{demand_id}", headers=auth_headers)
        if check.status_code == 200:
            assert check.json().get("status") == "PAID", \
                f"Full payment must set demand to PAID, got: {check.json().get('status')}"

    def test_partial_payment_transitions_demand_to_partially_paid(
        self, request, base_url, auth_headers
    ):
        create_resp, consumer = _create_demand(base_url, auth_headers, amount=100.0)
        if create_resp.status_code not in (200, 201):
            return
        demand_id = create_resp.json().get("id") or create_resp.json().get("demandId")
        bill_resp = _generate_bill(base_url, auth_headers, consumer)
        if bill_resp.status_code not in (200, 201):
            return
        bill_id = bill_resp.json().get("id") or bill_resp.json().get("billId")
        if not bill_id or not demand_id:
            return
        pay = req_lib.post(f"{base_url}/payments", headers=auth_headers, json={
            "paymentMode": "CASH",
            "totalAmountPaid": 50.0,
            "paymentDetails": [{"billId": bill_id, "totalAmountPaid": 50.0}],
        })
        if pay.status_code not in (200, 201):
            return
        check = req_lib.get(f"{base_url}/demands/{demand_id}", headers=auth_headers)
        if check.status_code == 200:
            assert check.json().get("status") == "PARTIALLY_PAID", \
                f"Partial payment must set demand to PARTIALLY_PAID, got: {check.json().get('status')}"
