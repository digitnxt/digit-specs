import uuid


def _unique_mobile():
    """Generate a valid 10-digit mobile number."""
    suffix = str(uuid.uuid4().int)[:9]
    return "9" + suffix[:9]


def make_individual(name=None, **overrides):
    """Minimal valid IndividualRequest payload (name + dateOfBirth required)."""
    base = {
        "name":        name or f"Test User {uuid.uuid4().hex[:6].upper()}",
        "dateOfBirth": "1990-01-15",
        "gender":      "MALE",
        "mobileNumber": _unique_mobile(),
    }
    return {**base, **overrides}


def make_individual_update(**overrides):
    """Valid update payload (PUT — full replace)."""
    base = {
        "name":        "Updated Test User",
        "dateOfBirth": "1985-06-20",
        "gender":      "FEMALE",
        "mobileNumber": _unique_mobile(),
    }
    return {**base, **overrides}


def make_individual_with_address(**overrides):
    """Individual payload including an address object."""
    base = make_individual()
    base["address"] = {
        "streetAddress": "123 Conformance Lane",
        "city":          "Testville",
        "state":         "TS",
        "countryCode":   "IN",
        "pincode":       "560001",
    }
    return {**base, **overrides}


def make_individual_with_document(**overrides):
    """Individual payload including a document (documentType + fileStoreId required)."""
    base = make_individual()
    base["documents"] = [
        {"documentType": "AADHAAR", "fileStoreId": f"fs-{uuid.uuid4().hex[:12]}"}
    ]
    return {**base, **overrides}


def make_invalid_individual(strategy="missing_required"):
    strategies = {
        "missing_required":   {},
        "name_too_short":     {"name": "X", "dateOfBirth": "1990-01-01"},
        "missing_dob":        {"name": "Valid Name"},
        "invalid_dob_format": {"name": "Valid Name", "dateOfBirth": "01-01-1990"},
        "invalid_gender":     {"name": "Valid Name", "dateOfBirth": "1990-01-01",
                               "gender": "UNKNOWN"},
        "mobile_too_short":   {"name": "Valid Name", "dateOfBirth": "1990-01-01",
                               "mobileNumber": "123"},
        "invalid_email":      {"name": "Valid Name", "dateOfBirth": "1990-01-01",
                               "email": "not-an-email"},
    }
    return strategies.get(strategy, {})


def make_config(key=None, value=None, **overrides):
    """Minimal valid Config upsert payload."""
    base = {
        "key":   key or f"conf.{uuid.uuid4().hex[:8]}",
        "value": value or "conformance-test-value",
    }
    return {**base, **overrides}


def make_invalid_config(strategy="missing_required"):
    strategies = {
        "missing_required": {},
        "missing_key":      {"value": "some-value"},
        "missing_value":    {"key": "some.key"},
        "key_too_short":    {"key": "x", "value": "v"},
    }
    return strategies.get(strategy, {})
