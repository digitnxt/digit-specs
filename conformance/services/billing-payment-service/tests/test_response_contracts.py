import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import (
    assert_gateway_headers,
    assert_json_content_type,
    assert_service_response_headers,
    assert_required_fields,
    assert_field_types,
    assert_enum_values,
    DEMAND_STATUSES,
    BILL_STATUSES,
    COLLECTION_MODES,
    TAX_HEAD_CATS,
)
from tests.helpers.factories import (
    make_business_service,
    make_tax_head,
    make_demand,
    _bs_code,
    _th_code,
)


def _send(node, method, url, headers=None, json_body=None, params=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body, params=params)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── BusinessService ───────────────────────────────────────────────────────────

class TestBusinessServiceSearchContract:
    def test_search_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/business-services", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list)

    def test_business_service_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/business-services", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json():
            assert_required_fields(item, ["code", "name", "isActive", "currency",
                                          "effectiveFrom", "billExpiryDays"])
            assert_field_types(item, {"id": str, "code": str, "name": str,
                                      "isActive": bool, "currency": str, "billExpiryDays": int})
            assert_enum_values(item, {"collectionMode": COLLECTION_MODES})


class TestBusinessServiceCreateContract:
    def test_create_returns_201_array(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _bs_code()
        response = _send(request.node, "POST", f"{base_url}/business-services",
                         headers=auth_headers, json_body=[make_business_service(code=code)])

        assert response.status_code == 201
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert isinstance(body, list) and len(body) >= 1
        assert_required_fields(body[0], ["id", "code", "name", "isActive", "currency"])
        assert body[0]["code"] == code

        req_lib.delete(f"{base_url}/business-services/{code}", headers=auth_headers)

    def test_get_by_code_returns_single_object(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _bs_code()
        req_lib.post(f"{base_url}/business-services",
                     json=[make_business_service(code=code)], headers=auth_headers)

        response = _send(request.node, "GET", f"{base_url}/business-services/{code}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert_required_fields(body, ["id", "code", "name", "isActive"])
        assert body["code"] == code

        req_lib.delete(f"{base_url}/business-services/{code}", headers=auth_headers)

    def test_get_nonexistent_code_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET",
                         f"{base_url}/business-services/DOES-NOT-EXIST-XYZ",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)


# ── TaxHead ───────────────────────────────────────────────────────────────────

class TestTaxHeadSearchContract:
    def test_search_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/tax-heads", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list)

    def test_tax_head_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/tax-heads", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json():
            assert_required_fields(item, ["code", "name", "businessServiceCode", "order",
                                          "effectiveFrom", "isActive"])
            assert_field_types(item, {"id": str, "code": str, "name": str,
                                      "businessServiceCode": str, "order": int, "isActive": bool})
            assert_enum_values(item, {"category": TAX_HEAD_CATS})


class TestTaxHeadCreateContract:
    def test_create_returns_201_array(self, request, base_url, auth_headers, gateway_headers_spec):
        bs_code = _bs_code()
        th_code = _th_code()
        req_lib.post(f"{base_url}/business-services",
                     json=[make_business_service(code=bs_code)], headers=auth_headers)

        response = _send(request.node, "POST", f"{base_url}/tax-heads",
                         headers=auth_headers,
                         json_body=[make_tax_head(bs_code=bs_code, code=th_code)])
        assert response.status_code == 201
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)

        body = response.json()
        assert isinstance(body, list) and len(body) >= 1
        assert_required_fields(body[0], ["id", "code", "name", "businessServiceCode"])
        assert body[0]["code"] == th_code

        req_lib.delete(f"{base_url}/tax-heads/{th_code}", headers=auth_headers)
        req_lib.delete(f"{base_url}/business-services/{bs_code}", headers=auth_headers)

    def test_get_by_code_returns_single_object(self, request, base_url, auth_headers, gateway_headers_spec):
        bs_code = _bs_code()
        th_code = _th_code()
        req_lib.post(f"{base_url}/business-services",
                     json=[make_business_service(code=bs_code)], headers=auth_headers)
        req_lib.post(f"{base_url}/tax-heads",
                     json=[make_tax_head(bs_code=bs_code, code=th_code)], headers=auth_headers)

        response = _send(request.node, "GET", f"{base_url}/tax-heads/{th_code}",
                         headers=auth_headers)
        assert response.status_code == 200
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert response.json()["code"] == th_code

        req_lib.delete(f"{base_url}/tax-heads/{th_code}", headers=auth_headers)
        req_lib.delete(f"{base_url}/business-services/{bs_code}", headers=auth_headers)

    def test_get_nonexistent_tax_head_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/tax-heads/NOTEXIST_XYZ",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── Demand ────────────────────────────────────────────────────────────────────

class TestDemandSearchContract:
    def test_search_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/demands", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list)

    def test_demand_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/demands", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json():
            assert_required_fields(item, ["id", "businessServiceCode", "consumerCode",
                                          "periodFrom", "periodTo", "lineItems"])
            assert_field_types(item, {"id": str, "businessServiceCode": str, "consumerCode": str})
            assert_enum_values(item, {"status": DEMAND_STATUSES})
            assert isinstance(item["lineItems"], list)

    def test_demand_get_by_id_returns_404_for_unknown(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/demands/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── Bills ─────────────────────────────────────────────────────────────────────

class TestBillSearchContract:
    def test_search_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/bills", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list)

    def test_bill_item_shape(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/bills", headers=auth_headers)
        assert response.status_code == 200

        for item in response.json():
            assert_field_types(item, {"id": str, "businessServiceCode": str})
            assert_enum_values(item, {"status": BILL_STATUSES})


# ── Payments ──────────────────────────────────────────────────────────────────

class TestPaymentSearchContract:
    def test_search_returns_array(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/payments", headers=auth_headers)

        assert response.status_code == 200
        assert_json_content_type(response)
        assert_service_response_headers(response)
        assert_gateway_headers(response, gateway_headers_spec)
        assert isinstance(response.json(), list)

    def test_payment_get_by_id_returns_404_for_unknown(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/payments/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
