import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
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


def _send(node, method, url, headers=None, json_body=None, params=None):
    """Prepare, attach cURL (for HTML report), then send."""
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


def _cleanup(url, headers):
    try:
        req_lib.delete(url, headers=headers)
    except Exception:
        pass


class TestBusinessServiceLifecycle:
    def test_create_read_update_patch_delete(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _bs_code()
        try:
            # 1. CREATE
            r = _send(request.node, "POST", f"{base_url}/business-services",
                      headers=auth_headers, json_body=[make_business_service(code=code)])
            assert r.status_code == 201, f"Create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            svc = r.json()[0]
            assert svc["code"] == code

            # 2. GET by code
            r = _send(request.node, "GET", f"{base_url}/business-services/{code}",
                      headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["code"] == code
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH by code filter
            r = _send(request.node, "GET", f"{base_url}/business-services",
                      headers=auth_headers, params={"code": code})
            assert r.status_code == 200
            assert code in [s["code"] for s in r.json()]

            # 4. PUT (full replace)
            r = _send(request.node, "PUT", f"{base_url}/business-services/{code}",
                      headers=auth_headers, json_body=make_bs_update(code=code))
            assert r.status_code == 200, f"PUT failed: {r.text}"
            assert r.json()["name"] == "Updated Conformance Service"
            assert_gateway_headers(r, gateway_headers_spec)

            # 5. PATCH (partial update)
            r = _send(request.node, "PATCH", f"{base_url}/business-services/{code}",
                      headers=auth_headers, json_body={"billExpiryDays": 90})
            assert r.status_code == 200, f"PATCH failed: {r.text}"
            assert r.json()["billExpiryDays"] == 90
            assert_gateway_headers(r, gateway_headers_spec)

            # 6. SOFT-DELETE
            r = _send(request.node, "DELETE", f"{base_url}/business-services/{code}",
                      headers=auth_headers)
            assert r.status_code == 200, f"Delete failed: {r.text}"
            assert r.json().get("deleted") is True
            assert_gateway_headers(r, gateway_headers_spec)
            code = None

        finally:
            if code:
                _cleanup(f"{base_url}/business-services/{code}", auth_headers)


class TestTaxHeadLifecycle:
    def test_create_read_update_delete_tax_head(self, request, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        try:
            r = _send(request.node, "POST", f"{base_url}/business-services",
                      headers=auth_headers, json_body=[make_business_service(code=bs)])
            assert r.status_code == 201, f"BS create failed: {r.text}"

            # 1. CREATE TaxHead
            r = _send(request.node, "POST", f"{base_url}/tax-heads",
                      headers=auth_headers, json_body=[make_tax_head(bs_code=bs, code=th)])
            assert r.status_code == 201, f"TaxHead create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)
            assert r.json()[0]["code"] == th

            # 2. GET by code
            r = _send(request.node, "GET", f"{base_url}/tax-heads/{th}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["code"] == th
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH
            r = _send(request.node, "GET", f"{base_url}/tax-heads",
                      headers=auth_headers, params={"businessServiceCode": bs})
            assert r.status_code == 200
            assert any(t["code"] == th for t in r.json())

            # 4. PUT
            r = _send(request.node, "PUT", f"{base_url}/tax-heads/{th}",
                      headers=auth_headers, json_body=make_th_update(bs_code=bs))
            assert r.status_code == 200, f"TaxHead PUT failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            # 5. PATCH
            r = _send(request.node, "PATCH", f"{base_url}/tax-heads/{th}",
                      headers=auth_headers, json_body={"isActive": True})
            assert r.status_code == 200, f"TaxHead PATCH failed: {r.text}"

            # 6. SOFT-DELETE
            r = _send(request.node, "DELETE", f"{base_url}/tax-heads/{th}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json().get("deleted") is True
            th = None

        finally:
            if th:
                _cleanup(f"{base_url}/tax-heads/{th}", auth_headers)
            _cleanup(f"{base_url}/business-services/{bs}", auth_headers)


class TestDemandLifecycle:
    def test_create_read_patch_demand(self, request, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        demand_id = None
        try:
            req_lib.post(f"{base_url}/business-services",
                         json=[make_business_service(code=bs)], headers=auth_headers)
            req_lib.post(f"{base_url}/tax-heads",
                         json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)

            consumer_code = f"CONS-{bs[:8]}"

            # 1. CREATE demand
            r = _send(request.node, "POST", f"{base_url}/demands",
                      headers=auth_headers,
                      json_body=[make_demand(bs_code=bs, tax_head_code=th,
                                             consumer_code=consumer_code)])
            assert r.status_code in (201, 207), f"Demand create failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            assert len(demands) >= 1
            demand_id = demands[0]["id"]

            # 2. GET by ID
            r = _send(request.node, "GET", f"{base_url}/demands/{demand_id}", headers=auth_headers)
            assert r.status_code == 200
            assert r.json()["id"] == demand_id
            assert_gateway_headers(r, gateway_headers_spec)

            # 3. SEARCH
            r = _send(request.node, "GET", f"{base_url}/demands",
                      headers=auth_headers, params={"consumerCode": consumer_code})
            assert r.status_code == 200
            assert any(d["id"] == demand_id for d in r.json())

            # 4. PATCH
            r = _send(request.node, "PATCH", f"{base_url}/demands/{demand_id}",
                      headers=auth_headers,
                      json_body={"lineItems": [{"taxHeadCode": th, "amount": 5000.00}]})
            assert r.status_code == 200, f"Demand PATCH failed: {r.text}"
            assert_gateway_headers(r, gateway_headers_spec)

        finally:
            _cleanup(f"{base_url}/tax-heads/{th}", auth_headers)
            _cleanup(f"{base_url}/business-services/{bs}", auth_headers)

    def test_demand_freeze_transition(self, request, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        demand_id = None
        try:
            req_lib.post(f"{base_url}/business-services",
                         json=[make_business_service(code=bs)], headers=auth_headers)
            req_lib.post(f"{base_url}/tax-heads",
                         json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)

            r = _send(request.node, "POST", f"{base_url}/demands",
                      headers=auth_headers,
                      json_body=[make_demand(bs_code=bs, tax_head_code=th)])
            assert r.status_code in (201, 207), f"Demand create failed: {r.text}"
            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            if not demands:
                pytest.skip("No demand created — skipping freeze test")
            demand_id = demands[0]["id"]

            r = _send(request.node, "POST",
                      f"{base_url}/demands/{demand_id}/freeze", headers=auth_headers)
            assert r.status_code in (200, 409), f"Freeze failed unexpectedly: {r.text}"
            if r.status_code == 200:
                assert r.json().get("status") == "FROZEN"
                assert_gateway_headers(r, gateway_headers_spec)

        finally:
            _cleanup(f"{base_url}/tax-heads/{th}", auth_headers)
            _cleanup(f"{base_url}/business-services/{bs}", auth_headers)

    def test_demand_cancel_transition(self, request, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        try:
            req_lib.post(f"{base_url}/business-services",
                         json=[make_business_service(code=bs)], headers=auth_headers)
            req_lib.post(f"{base_url}/tax-heads",
                         json=[make_tax_head(bs_code=bs, code=th)], headers=auth_headers)

            r = _send(request.node, "POST", f"{base_url}/demands",
                      headers=auth_headers,
                      json_body=[make_demand(bs_code=bs, tax_head_code=th)])
            assert r.status_code in (201, 207)
            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            if not demands:
                pytest.skip("No demand created — skipping cancel test")
            demand_id = demands[0]["id"]

            r = _send(request.node, "POST", f"{base_url}/demands/{demand_id}/cancel",
                      headers=auth_headers,
                      json_body={"reason": "Conformance test cancellation"})
            assert r.status_code in (200, 409, 422), f"Cancel response: {r.text}"
            if r.status_code == 200:
                assert r.json().get("status") == "CANCELLED"
                assert_gateway_headers(r, gateway_headers_spec)

        finally:
            _cleanup(f"{base_url}/tax-heads/{th}", auth_headers)
            _cleanup(f"{base_url}/business-services/{bs}", auth_headers)


class TestFullBillingCycle:
    """End-to-end: BusinessService → TaxHead → Demand → Bill → Payment."""

    def test_full_billing_lifecycle(self, request, base_url, auth_headers, gateway_headers_spec):
        bs = _bs_code()
        th = _th_code()
        consumer = f"CONS-CYCLE-{bs[:8]}"
        bill_id = None
        try:
            r = _send(request.node, "POST", f"{base_url}/business-services",
                      headers=auth_headers, json_body=[make_business_service(code=bs)])
            assert r.status_code == 201, f"BS create failed: {r.text}"

            r = _send(request.node, "POST", f"{base_url}/tax-heads",
                      headers=auth_headers, json_body=[make_tax_head(bs_code=bs, code=th)])
            assert r.status_code == 201, f"TaxHead create failed: {r.text}"

            r = _send(request.node, "POST", f"{base_url}/demands",
                      headers=auth_headers,
                      json_body=[make_demand(bs_code=bs, tax_head_code=th,
                                             consumer_code=consumer, status="ACTIVE")])
            assert r.status_code in (201, 207), f"Demand create failed: {r.text}"
            body = r.json()
            demands = body if isinstance(body, list) else body.get("succeeded", [])
            if not demands:
                pytest.skip("No demand created — cannot test bill generation")

            r = _send(request.node, "POST", f"{base_url}/bills/generate",
                      headers=auth_headers,
                      json_body=make_generate_bill_criteria(bs, consumer))
            if r.status_code not in (201, 409):
                pytest.skip(f"Bill generation returned {r.status_code} — skipping payment test")
            if r.status_code == 201:
                assert_gateway_headers(r, gateway_headers_spec)
                bill = r.json()
                assert_required_fields(bill, ["id"])
                bill_id = bill["id"]
                total  = bill.get("totalAmount", 4500.00)

                r = _send(request.node, "POST", f"{base_url}/payments",
                          headers=auth_headers,
                          json_body=make_payment(bill_id=bill_id, amount=total))
                assert r.status_code in (201, 409), f"Payment create: {r.text}"
                if r.status_code == 201:
                    assert_gateway_headers(r, gateway_headers_spec)
                    payment = r.json()
                    assert_required_fields(payment, ["id"])

                    r = _send(request.node, "GET",
                              f"{base_url}/payments/{payment['id']}", headers=auth_headers)
                    assert r.status_code == 200
                    assert r.json()["id"] == payment["id"]

        finally:
            _cleanup(f"{base_url}/tax-heads/{th}", auth_headers)
            _cleanup(f"{base_url}/business-services/{bs}", auth_headers)
