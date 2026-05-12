import time
import uuid

# epoch ms helpers
_NOW   = int(time.time() * 1000)
_YEAR  = 365 * 24 * 3600 * 1000
_START = 1_735_669_800_000   # 2025-01-01


def _bs_code():
    """Valid BusinessService code: uppercase, starts with letter, 2-32 chars."""
    return "BS" + uuid.uuid4().hex[:6].upper()


def _th_code():
    """Valid TaxHead code: uppercase, starts with letter, 2-64 chars."""
    return "TH" + uuid.uuid4().hex[:6].upper()


# ── BusinessService ───────────────────────────────────────────────────────────

def make_business_service(code=None, **overrides):
    """Minimal valid BusinessServiceCreate payload."""
    base = {
        "code":           code or _bs_code(),
        "name":           "Conformance Test Service",
        "isActive":       True,
        "currency":       "INR",
        "effectiveFrom":  _START,
        "billExpiryDays": 30,
        "collectionMode": "BOTH",
        "allowedPaymentModes": ["CASH", "ONLINE"],
        "partialPaymentAllowed": True,
    }
    return {**base, **overrides}


def make_bs_update(code, **overrides):
    """Minimal valid BusinessServiceUpdateByCode payload (no code field)."""
    base = {
        "name":           "Updated Conformance Service",
        "isActive":       True,
        "allowedPaymentModes": ["CASH", "ONLINE"],
        "currency":       "INR",
        "effectiveFrom":  _START,
        "billExpiryDays": 60,
        "collectionMode": "ONLINE",
    }
    return {**base, **overrides}


def make_invalid_business_service(strategy="missing_required"):
    strategies = {
        "missing_required":    {},
        "invalid_code_pattern":{"code": "lowercase-code", "name": "X", "isActive": True,
                                "currency": "INR", "effectiveFrom": _START, "billExpiryDays": 30},
        "wrong_currency":      {"code": _bs_code(), "name": "X", "isActive": True,
                                "currency": "inr",  "effectiveFrom": _START, "billExpiryDays": 30},
        "negative_expiry":     {"code": _bs_code(), "name": "X", "isActive": True,
                                "currency": "INR",  "effectiveFrom": _START, "billExpiryDays": -1},
    }
    return strategies.get(strategy, {})


# ── TaxHead ───────────────────────────────────────────────────────────────────

def make_tax_head(bs_code, code=None, **overrides):
    """Minimal valid TaxHeadCreate payload."""
    base = {
        "code":                code or _th_code(),
        "name":                "Conformance Base Tax",
        "businessServiceCode": bs_code,
        "order":               1,
        "effectiveFrom":       _START,
        "isActive":            True,
        "category":            "TAX",
    }
    return {**base, **overrides}


def make_th_update(bs_code, **overrides):
    """Minimal valid TaxHeadUpdateByCode payload."""
    base = {
        "name":                "Updated Tax Head",
        "businessServiceCode": bs_code,
        "order":               2,
        "effectiveFrom":       _START,
        "isActive":            True,
        "category":            "TAX",
    }
    return {**base, **overrides}


def make_invalid_tax_head(strategy="missing_required"):
    strategies = {
        "missing_required":    {},
        "invalid_code_pattern":{"code": "lower_code", "name": "X", "businessServiceCode": "PT",
                                "order": 1, "effectiveFrom": _START, "isActive": True},
        "missing_bs_code":     {"code": _th_code(), "name": "X",
                                "order": 1, "effectiveFrom": _START, "isActive": True},
    }
    return strategies.get(strategy, {})


# ── Demand ────────────────────────────────────────────────────────────────────

def make_demand(bs_code, tax_head_code, consumer_code=None, **overrides):
    """Minimal valid DemandCreate payload."""
    base = {
        "businessServiceCode": bs_code,
        "periodFrom":          _START,
        "periodTo":            _START + _YEAR - 1,
        "consumerCode":        consumer_code or f"CONS-{uuid.uuid4().hex[:8].upper()}",
        "lineItems": [
            {"taxHeadCode": tax_head_code, "amount": 4500.00}
        ],
        "status": "ACTIVE",
    }
    return {**base, **overrides}


def make_invalid_demand(strategy="missing_required"):
    strategies = {
        "missing_required":        {},
        "missing_line_items":      {"businessServiceCode": "PT", "periodFrom": _START,
                                   "periodTo": _START + _YEAR, "consumerCode": "X"},
        "empty_line_items":        {"businessServiceCode": "PT", "periodFrom": _START,
                                   "periodTo": _START + _YEAR, "consumerCode": "X", "lineItems": []},
        "period_inverted":         {"businessServiceCode": "PT",
                                   "periodFrom": _START + _YEAR, "periodTo": _START,
                                   "consumerCode": "X",
                                   "lineItems": [{"taxHeadCode": "PT_BASE", "amount": 100}]},
    }
    return strategies.get(strategy, {})


# ── Bill ──────────────────────────────────────────────────────────────────────

def make_generate_bill_criteria(bs_code, consumer_code):
    """GenerateBillCriteria payload."""
    return {
        "businessServiceCode": bs_code,
        "consumerCode":        consumer_code,
    }


def make_bulk_bill_generator(bs_code):
    return {"businessServiceCode": bs_code}


def make_cancel_bill(bill_id):
    return {"billId": bill_id, "reason": "Conformance test cancellation"}


# ── Payment ───────────────────────────────────────────────────────────────────

def make_payment(bill_id, amount, **overrides):
    """Minimal valid PaymentCreate payload."""
    base = {
        "billId":          bill_id,
        "totalAmountPaid": amount,
        "paymentMode":     "CASH",
        "transactionDate": _NOW,
        "payer": {
            "name":         "Test Payer",
            "mobileNumber": "9876543210",
        },
    }
    return {**base, **overrides}


def make_invalid_payment(strategy="missing_required"):
    strategies = {
        "missing_required": {},
        "missing_bill_id":  {"totalAmountPaid": 100, "paymentMode": "CASH",
                             "transactionDate": _NOW},
        "negative_amount":  {"billId": str(uuid.uuid4()), "totalAmountPaid": -1,
                             "paymentMode": "CASH", "transactionDate": _NOW},
    }
    return strategies.get(strategy, {})
