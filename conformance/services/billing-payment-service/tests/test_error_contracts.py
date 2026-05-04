import uuid
import pytest
import requests as req_lib
from tests.helpers.curl_builder import attach_curl
from tests.helpers.validators import assert_error_schema, assert_gateway_headers, assert_json_content_type
from tests.helpers.factories import (
    make_invalid_business_service,
    make_invalid_tax_head,
    make_invalid_demand,
    make_invalid_payment,
    make_business_service,
    make_tax_head,
    _bs_code,
    _th_code,
)

SINGLE_ERROR_SCHEMA = {
    "type": "object",
    "properties": {
        "code":    {"type": "string"},
        "message": {"type": "string"},
    },
}
ERROR_ARRAY_SCHEMA = {"type": "array", "items": SINGLE_ERROR_SCHEMA}


def _send(node, method, url, headers=None, json_body=None):
    r = req_lib.Request(method, url, headers=headers, json=json_body)
    prepared = r.prepare()
    attach_curl(node, prepared)
    return req_lib.Session().send(prepared)


# ── BusinessService ───────────────────────────────────────────────────────────

class TestBusinessServiceNegativeContracts:
    def test_empty_body_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/business-services",
                         headers=auth_headers, json_body=[])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/business-services",
                         headers=auth_headers,
                         json_body=[make_invalid_business_service("missing_required")])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_code_pattern_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/business-services",
                         headers=auth_headers,
                         json_body=[make_invalid_business_service("invalid_code_pattern")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_wrong_currency_format_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/business-services",
                         headers=auth_headers,
                         json_body=[make_invalid_business_service("wrong_currency")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_duplicate_code_returns_409(self, request, base_url, auth_headers, gateway_headers_spec):
        code = _bs_code()
        r1 = _send(request.node, "POST", f"{base_url}/business-services",
                   headers=auth_headers, json_body=[make_business_service(code=code)])
        if r1.status_code != 201:
            pytest.skip("Could not create initial business service")
        try:
            r2 = _send(request.node, "POST", f"{base_url}/business-services",
                       headers=auth_headers, json_body=[make_business_service(code=code)])
            assert r2.status_code == 409
            assert_gateway_headers(r2, gateway_headers_spec)
        finally:
            req_lib.delete(f"{base_url}/business-services/{code}", headers=auth_headers)

    def test_missing_auth_returns_401(self, request, base_url, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/business-services")
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_token_returns_401(self, request, base_url, auth_headers, gateway_headers_spec):
        bad = {**auth_headers, "Authorization": "Bearer bad-token-xyz"}
        response = _send(request.node, "GET", f"{base_url}/business-services", headers=bad)
        assert response.status_code == 401
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_code_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT", f"{base_url}/business-services/GHOST_CODE",
                         headers=auth_headers,
                         json_body={"name": "X", "isActive": True, "currency": "INR",
                                    "effectiveFrom": 1735669800000, "billExpiryDays": 30})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── TaxHead ───────────────────────────────────────────────────────────────────

class TestTaxHeadNegativeContracts:
    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/tax-heads",
                         headers=auth_headers,
                         json_body=[make_invalid_tax_head("missing_required")])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_invalid_code_pattern_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/tax-heads",
                         headers=auth_headers,
                         json_body=[make_invalid_tax_head("invalid_code_pattern")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_missing_bs_code_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/tax-heads",
                         headers=auth_headers,
                         json_body=[make_invalid_tax_head("missing_bs_code")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_update_nonexistent_code_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PUT", f"{base_url}/tax-heads/GHOST_TH",
                         headers=auth_headers,
                         json_body={"name": "X", "businessServiceCode": "PT",
                                    "order": 1, "effectiveFrom": 1735669800000, "isActive": True})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── Demand ────────────────────────────────────────────────────────────────────

class TestDemandNegativeContracts:
    def test_missing_required_fields_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/demands",
                         headers=auth_headers,
                         json_body=[make_invalid_demand("missing_required")])
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_empty_line_items_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/demands",
                         headers=auth_headers,
                         json_body=[make_invalid_demand("empty_line_items")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_inverted_period_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/demands",
                         headers=auth_headers,
                         json_body=[make_invalid_demand("period_inverted")])
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_patch_nonexistent_demand_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "PATCH", f"{base_url}/demands/{uuid.uuid4()}",
                         headers=auth_headers,
                         json_body={"lineItems": [{"taxHeadCode": "PT_BASE", "amount": 100}]})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_freeze_nonexistent_demand_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/demands/{uuid.uuid4()}/freeze", headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)

    def test_cancel_nonexistent_demand_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST",
                         f"{base_url}/demands/{uuid.uuid4()}/cancel",
                         headers=auth_headers, json_body={"reason": "test"})
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)


# ── Bill ──────────────────────────────────────────────────────────────────────

class TestBillNegativeContracts:
    def test_generate_bill_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/bills/generate",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_generate_bill_unknown_consumer_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/bills/generate",
                         headers=auth_headers,
                         json_body={"businessServiceCode": "PT",
                                    "consumerCode": "NO-SUCH-CONSUMER-XYZ"})
        assert response.status_code in (404, 400)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_cancel_bill_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/bills/cancel",
                         headers=auth_headers, json_body={})
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)


# ── Payment ───────────────────────────────────────────────────────────────────

class TestPaymentNegativeContracts:
    def test_create_payment_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/payments",
                         headers=auth_headers,
                         json_body=make_invalid_payment("missing_required"))
        assert response.status_code == 400
        assert_json_content_type(response)
        assert_gateway_headers(response, gateway_headers_spec)

    def test_validate_payment_missing_required_returns_400(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "POST", f"{base_url}/payments/validate",
                         headers=auth_headers,
                         json_body=make_invalid_payment("missing_required"))
        assert response.status_code == 400
        assert_gateway_headers(response, gateway_headers_spec)

    def test_get_payment_nonexistent_returns_404(self, request, base_url, auth_headers, gateway_headers_spec):
        response = _send(request.node, "GET", f"{base_url}/payments/{uuid.uuid4()}",
                         headers=auth_headers)
        assert response.status_code == 404
        assert_gateway_headers(response, gateway_headers_spec)
