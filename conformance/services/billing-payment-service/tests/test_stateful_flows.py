import pytest
import requests
from tests.helpers.factories import (
    make_business_service,
    make_bs_update,
    make_tax_head,
    make_th_update,
    make_demand,
    make_generate_bill_criteria,
    make_payment,
    _bs_code,
    _th_code,
)
from tests.helpers.validators import assert_gateway_headers, assert_required_fields


class TestBusinessServiceLifecycle:
    def test_create_read_update_patch_delete(self, base_url, auth_headers, gateway_headers_spec):
        code = _bs_code()
        try:
            # 1. CREATE
            r = requests.post(f"{base_url}/business-services",
                              json=[make_business_service(code=code)], headers=auth_headers)
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            svc = r.json()[0]
            assert svc["code"] == code

            # 2. GET by code
            r = requests.get(f"{base_url}/business-services/{code}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["code"] == code
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH by code filter
            r = requests.get(f"{base_url}/business-services",
                             params={"code": code}, headers=auth_headers)
            assert r.status_code == 200
            codes = [s["code"] for s in r.json()]
            assert code in codes

            # 4. PUT (full replace)
            r = requests.put(f"{base_url}/business-services/{code}",
                             json=make_bs_update(code=code), headers=auth_headers)
            assert r.status_code == 200, f"PUT failed: {r.text}"
            assert r.json()["name"] == "Updated Conformance Service"
            assert_gateway_headers(r, gateway_headers_spec)

            # 5. PATCH (partial update)
            r = requests.patch(f"{base_url}/business-services/{code}",
                               json={"billExpiryDays": 90}, headers=auth_headers)
            assert r.status_code == 200, f"PATCH failed: {r.text}"
            assert r.json()["billExpiryDays"] == 90
            assert_gateway_headers(r, gateway_headers_spec)

            # 6. SOFT-DELETE (deactivate)
            r = requests.delete(f"{base_url}/business-services/{code}", headers=auth_headers)
            assert r.status_code == 200, f"Delete failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)
            code = None  # mark cleaned up

        finally:
            if code:
                requests.delete(f"{base_url}/business-services/{code}", headers=auth_headers)


class TestTaxHeadLifecycle:
    def test_create_read_update_delete_tax_head(self, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        try:
            # Setup: create parent BusinessService
            r = requests.post(f"{base_url}/business-services",
                              json=[make_business_service(code=bs)], headers=auth_headers)
            assert r.status_code == 201, f"BS create failed: {r.text}"

            # 1. CREATE TaxHead
            r = requests.post(f"{base_url}/tax-heads",
                              json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)
            assert r.status_code == 201, f"TaxHead create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert r.json()[0]["code"] == th

            # 2. GET by code
            r = requests.get(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["code"] == th
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH by businessServiceCode
            r = requests.get(f"{base_url}/tax-heads",
                             params={"businessServiceCode": bs}, headers=auth_headers)
            assert r.status_code == 200
            assert any(t["code"] == th for t in r.json())

            # 4. PUT (full replace)
            r = requests.put(f"{base_url}/tax-heads/{th}",
                             json=make_th_update(bs_code=bs), headers=auth_headers)
            assert r.status_code == 200, f"TaxHead PUT failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            # 5. PATCH
            r = requests.patch(f"{base_url}/tax-heads/{th}",
                               json={"isActive": True}, headers=auth_headers)
            assert r.status_code == 200, f"TaxHead PATCH failed: {r.text}"

            # 6. SOFT-DELETE
            r = requests.delete(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json().get("deleted") is True
            th = None

        finally:
            if th:
                requests.delete(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            requests.delete(f"{base_url}/business-services/{bs}", headers=auth_headers)


class TestDemandLifecycle:
    def test_create_read_patch_demand(self, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        demand_id = None
        try:
            # Setup: BusinessService + TaxHead
            r = requests.post(f"{base_url}/business-services",
                              json=[make_business_service(code=bs)], headers=auth_headers)
            assert r.status_code == 201, f"BS create failed: {r.text}"

            r = requests.post(f"{base_url}/tax-heads",
                              json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)
            assert r.status_code == 201, f"TaxHead create failed: {r.text}"

            consumer_code = f"CONS-{bs[:8]}"

            # 1. CREATE demand
            r = requests.post(f"{base_url}/demands",
                              json=[make_demand(bs_code=bs, tax_head_code=th,
                                               consumer_code=consumer_code)],
                              headers=auth_headers)
            assert r.status_code in (201, 207), f"Demand create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            assert len(demands) >= 1, "No demand returned in response"
            demand_id = demands[0]["id"]

            # 2. GET by ID
            r = requests.get(f"{base_url}/demands/{demand_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == demand_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH by consumer code
            r = requests.get(f"{base_url}/demands",
                             params={"consumerCode": consumer_code}, headers=auth_headers)
            assert r.status_code == 200
            assert any(d["id"] == demand_id for d in r.json())

            # 4. PATCH demand
            r = requests.patch(f"{base_url}/demands/{demand_id}",
                               json={"lineItems": [{"taxHeadCode": th, "amount": 5000.00}]},
                               headers=auth_headers)
            assert r.status_code == 200, f"Demand PATCH failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

        finally:
            requests.delete(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            requests.delete(f"{base_url}/business-services/{bs}", headers=auth_headers)

    def test_demand_freeze_transition(self, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        demand_id = None
        try:
            requests.post(f"{base_url}/business-services",
                          json=[make_business_service(code=bs)], headers=auth_headers)
            requests.post(f"{base_url}/tax-heads",
                          json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)

            r = requests.post(f"{base_url}/demands",
                              json=[make_demand(bs_code=bs, tax_head_code=th)],
                              headers=auth_headers)
            assert r.status_code in (201, 207), f"Demand create failed: {r.text}"
            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            if not demands:
                pytest.skip("No demand created — skipping freeze test")
            demand_id = demands[0]["id"]

            # FREEZE
            r = requests.post(f"{base_url}/demands/{demand_id}/freeze", headers=auth_headers)
            assert r.status_code in (200, 409), f"Freeze failed unexpectedly: {r.text}"
            if r.status_code == 200:
                assert r.json().get("status") == "FROZEN"
                assert_gateway_headers(r, gateway_headers_spec)

        finally:
            requests.delete(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            requests.delete(f"{base_url}/business-services/{bs}", headers=auth_headers)

    def test_demand_cancel_transition(self, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        try:
            requests.post(f"{base_url}/business-services",
                          json=[make_business_service(code=bs)], headers=auth_headers)
            requests.post(f"{base_url}/tax-heads",
                          json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)

            r = requests.post(f"{base_url}/demands",
                              json=[make_demand(bs_code=bs, tax_head_code=th)],
                              headers=auth_headers)
            assert r.status_code in (201, 207)
            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            if not demands:
                pytest.skip("No demand created — skipping cancel test")
            demand_id = demands[0]["id"]

            # CANCEL
            r = requests.post(f"{base_url}/demands/{demand_id}/cancel",
                              json={"reason": "Conformance test cancellation"},
                              headers=auth_headers)
            assert r.status_code in (200, 409, 422), f"Cancel response: {r.text}"
            if r.status_code == 200:
                assert r.json().get("status") == "CANCELLED"
                assert_gateway_headers(r, gateway_headers_spec)

        finally:
            requests.delete(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            requests.delete(f"{base_url}/business-services/{bs}", headers=auth_headers)


class TestFullBillingCycle:
    """End-to-end: BusinessService → TaxHead → Demand → Bill → Payment."""

    def test_full_billing_lifecycle(self, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        consumer = f"CONS-CYCLE-{bs[:8]}"
        bill_id  = None
        try:
            # 1. BusinessService
            r = requests.post(f"{base_url}/business-services",
                              json=[make_business_service(code=bs)], headers=auth_headers)
            assert r.status_code == 201, f"BS create failed: {r.text}"

            # 2. TaxHead
            r = requests.post(f"{base_url}/tax-heads",
                              json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)
            assert r.status_code == 201, f"TaxHead create failed: {r.text}"

            # 3. Demand (ACTIVE status — eligible for billing)
            r = requests.post(f"{base_url}/demands",
                              json=[make_demand(bs_code=bs, tax_head_code=th,
                                               consumer_code=consumer, status="ACTIVE")],
                              headers=auth_headers)
            assert r.status_code in (201, 207), f"Demand create failed: {r.text}"
            body   = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            if not demands:
                pytest.skip("No demand created — cannot test bill generation")

            # 4. Generate bill
            r = requests.post(f"{base_url}/bills/generate",
                              json=make_generate_bill_criteria(bs, consumer),
                              headers=auth_headers)
            if r.status_code not in (201, 409):
                pytest.skip(f"Bill generation returned {r.status_code} — skipping payment test")
            if r.status_code == 201:
                assert_gateway_headers(r, gateway_headers_spec)
                bill = r.json()
                assert_required_fields(bill, ["id"])
                bill_id = bill["id"]
                total   = bill.get("totalAmount", 4500.00)

                # 5. Create payment
                r = requests.post(f"{base_url}/payments",
                                  json=make_payment(bill_id=bill_id, amount=total),
                                  headers=auth_headers)
                assert r.status_code in (201, 409), f"Payment create: {r.text}"
                if r.status_code == 201:
                    assert_gateway_headers(r, gateway_headers_spec)
                    payment = r.json()
                    assert_required_fields(payment, ["id"])

                    # 6. Retrieve payment by ID
                    r = requests.get(f"{base_url}/payments/{payment['id']}", headers=auth_headers)
                    assert r.status_code == 200
                    assert r.json()["id"] == payment["id"]

        finally:
            requests.delete(f"{base_url}/tax-heads/{th}", headers=auth_headers)
            requests.delete(f"{base_url}/business-services/{bs}", headers=auth_headers)
