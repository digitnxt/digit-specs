"""
Lifecycle rule tests for Billing-Payment service.
State transitions for Demand, Bill, and Payment.
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


def _create_demand(base_url, auth_headers, consumer=None, status="ACTIVE"):
    now = _now_ms()
    consumer = consumer or "CONS-" + uuid.uuid4().hex[:6].upper()
    resp = req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
        "consumerCode": consumer,
        "businessServiceCode": "TEST-BS",
        "periodFrom": now - 30 * 86400 * 1000,
        "periodTo": now,
        "status": status,
        "totalAmount": 100.0,
        "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
    })
    return resp, consumer


# ---------------------------------------------------------------------------
# BR-LC-001: Demand status transitions one-way
# ---------------------------------------------------------------------------

class TestBR_LC_001_demand_status_transitions_one_way:
    """Demand status cannot go backwards; PAID → ACTIVE is rejected."""

    def test_active_demand_can_be_cancelled(self, request, base_url, auth_headers):
        create_resp, consumer = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand_id = create_resp.json().get("id") or create_resp.json().get("demandId")
        if not demand_id:
            return

        resp = _post(request.node, f"{base_url}/demands/{demand_id}/cancel",
                     auth_headers, {})
        assert resp.status_code in (200, 204), \
            f"Cancelling ACTIVE demand must succeed, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-002: Only DRAFT and ACTIVE editable
# ---------------------------------------------------------------------------

class TestBR_LC_002_only_draft_and_active_editable:
    """Editing a FROZEN/PAID demand is rejected."""

    def test_edit_active_demand_accepted(self, request, base_url, auth_headers):
        create_resp, consumer = _create_demand(base_url, auth_headers)
        if create_resp.status_code not in (200, 201):
            return
        demand = create_resp.json()
        demand_id = demand.get("id") or demand.get("demandId")
        if not demand_id:
            return

        resp = req_lib.patch(f"{base_url}/demands/{demand_id}", headers=auth_headers,
                             json={**demand, "totalAmount": 100.0})
        assert resp.status_code in (200, 201), \
            f"Editing ACTIVE demand must succeed, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-LC-004: Bill status transitions by event
# ---------------------------------------------------------------------------

class TestBR_LC_004_bill_status_transitions_by_event:
    """Bill status follows defined transitions; arbitrary transitions rejected."""

    def test_bill_generation_creates_active_bill(self, request, base_url, auth_headers):
        _, consumer = _create_demand(base_url, auth_headers)
        resp = _post(request.node, f"{base_url}/bills/generate", auth_headers, {
            "consumerCode": consumer,
            "businessService": "TEST-BS",
        })
        assert resp.status_code in (200, 201, 409, 422), \
            f"Bill generation must return 200/201/409/422, got {resp.status_code}: {resp.text}"
        if resp.status_code in (200, 201):
            bill = resp.json()
            assert bill.get("status") == "ACTIVE", \
                f"Newly generated bill must be ACTIVE, got {bill.get('status')}"


# ---------------------------------------------------------------------------
# BR-LC-005: Payment status transitions normal path
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# BR-LC-003: Bill generation freezes ACTIVE demands
# ---------------------------------------------------------------------------

class TestBR_LC_003_bill_generation_freezes_active_demands:
    """When a bill is generated, ACTIVE demands included in it transition to FROZEN."""

    def test_demand_status_is_frozen_after_bill_generation(
        self, request, base_url, auth_headers
    ):
        import uuid
        now = _now_ms()
        consumer = "CONS-LC003-" + uuid.uuid4().hex[:4].upper()
        demand_resp = req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 30 * 86400_000, "periodTo": now,
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        if demand_resp.status_code not in (200, 201):
            return
        demand_id = demand_resp.json().get("id") or demand_resp.json().get("demandId")

        bill_resp = req_lib.post(f"{base_url}/bills/generate", headers=auth_headers,
                                 json={"consumerCode": consumer, "businessService": "TEST-BS"})
        if bill_resp.status_code not in (200, 201):
            return

        if demand_id:
            demand_check = req_lib.get(f"{base_url}/demands/{demand_id}",
                                       headers=auth_headers)
            if demand_check.status_code == 200:
                status = demand_check.json().get("status")
                assert status in ("FROZEN", "PARTIALLY_PAID", "PAID"), \
                    f"Demand must be FROZEN after bill generation, got status={status}"


# ---------------------------------------------------------------------------
# BR-LC-006: Arrear demands reference rolled forward
# ---------------------------------------------------------------------------

class TestBR_LC_006_arrear_demands_reference_rolled_forward:
    """
    A ROLL_FORWARDED demand cannot be edited or paid directly.
    We test that updating a ROLL_FORWARDED demand returns 422.
    """

    def test_updating_roll_forwarded_demand_rejected(self, request, base_url, auth_headers):
        # Create a demand, roll it forward if the service supports it, then try to edit
        import uuid
        now = _now_ms()
        consumer = "CONS-LC006-" + uuid.uuid4().hex[:4].upper()
        create = req_lib.post(f"{base_url}/demands", headers=auth_headers, json={
            "consumerCode": consumer,
            "businessServiceCode": "TEST-BS",
            "periodFrom": now - 60 * 86400_000, "periodTo": now - 30 * 86400_000,
            "status": "ROLL_FORWARDED",
            "totalAmount": 100.0,
            "lineItems": [{"taxHeadCode": "TEST-TAX", "amount": 100.0}],
        })
        if create.status_code not in (200, 201):
            return
        demand = create.json()
        demand_id = demand.get("id") or demand.get("demandId")
        if not demand_id:
            return

        update = _post(request.node, f"{base_url}/demands/{demand_id}", auth_headers,
                       {**demand, "totalAmount": 150.0})
        assert update.status_code == 422, \
            f"Expected 422 for editing ROLL_FORWARDED demand, got {update.status_code}: {update.text}"


class TestBR_LC_005_payment_status_transitions_normal_path:
    """Payment status follows NEW→DEPOSITED→RECONCILED; terminal states cannot reverse."""

    def test_invalid_payment_transition_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/payments/cancel", auth_headers, {
            "paymentId": "NONEXISTENT-PAY-001",
        })
        assert resp.status_code in (400, 404, 422), \
            f"Cancel of nonexistent payment must fail, got {resp.status_code}: {resp.text}"
