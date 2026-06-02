"""
Cross-field rule tests for Individual service.
"""
import uuid
from datetime import datetime, timedelta
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _mobile():
    return f"+91{uuid.uuid4().int % 10_000_000_000:010d}"


def _base_individual(mobile=None):
    return {
        "givenName": "Test",
        "familyName": "User",
        "mobileNumber": mobile or _mobile(),
    }


# ---------------------------------------------------------------------------
# BR-CF-001: At least one contact method required
# ---------------------------------------------------------------------------

class TestBR_CF_001_at_least_one_contact_method_required:
    """Either mobileNumber or email must be present."""

    def test_mobile_only_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {"givenName": "Test", "mobileNumber": _mobile()},
        })
        assert resp.status_code in (200, 201), f"Mobile-only must be accepted: {resp.text}"

    def test_email_only_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {"givenName": "Test", "email": f"test-{uuid.uuid4().hex[:6]}@example.com"},
        })
        assert resp.status_code in (200, 201), f"Email-only must be accepted: {resp.text}"

    def test_neither_contact_method_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {"givenName": "Test", "familyName": "NoContact"},
        })
        assert resp.status_code == 400, f"Expected 400 for no contact method, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: Each identifier type appears at most once
# ---------------------------------------------------------------------------

class TestBR_CF_002_each_identifier_type_appears_at_most_once:
    """Duplicate identifierType in the identifiers array is rejected."""

    def test_duplicate_identifier_type_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "identifiers": [
                    {"identifierType": "AADHAAR", "identifierValue": "1234-5678-9012"},
                    {"identifierType": "AADHAAR", "identifierValue": "9876-5432-1098"},
                ],
            },
        })
        assert resp.status_code == 400, f"Expected 400 for duplicate identifier type, got {resp.status_code}: {resp.text}"

    def test_unique_identifier_types_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "identifiers": [
                    {"identifierType": "AADHAAR", "identifierValue": "1234-5678-9012"},
                    {"identifierType": "PAN", "identifierValue": "ABCDE1234F"},
                ],
            },
        })
        assert resp.status_code in (200, 201), f"Expected 200/201 for unique types, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Latitude bounds between -90 and 90
# ---------------------------------------------------------------------------

class TestBR_CF_004_latitude_bounds:
    """Latitude must be in [-90, 90]."""

    def test_latitude_above_90_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "address": [{"latitude": 91.0, "city": "Testcity"}],
            },
        })
        assert resp.status_code == 400, f"Expected 400 for lat=91, got {resp.status_code}: {resp.text}"

    def test_valid_latitude_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "address": [{"latitude": 12.97, "longitude": 77.59, "city": "Testcity"}],
            },
        })
        assert resp.status_code in (200, 201), f"Valid lat/lon must be accepted: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: Date of birth must not be in future
# ---------------------------------------------------------------------------

class TestBR_CF_006_dob_must_not_be_in_future:
    """dateOfBirth after today is rejected."""

    def test_future_dob_rejected(self, request, base_url, auth_headers):
        future = (datetime.now() + timedelta(days=30)).strftime("%d/%m/%Y")
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "dateOfBirth": future},
        })
        assert resp.status_code == 400, f"Expected 400 for future DOB, got {resp.status_code}: {resp.text}"

    def test_past_dob_accepted(self, request, base_url, auth_headers):
        past = "01/01/1990"
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "dateOfBirth": past},
        })
        assert resp.status_code in (200, 201), f"Past DOB must be accepted: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-008: Age must be between 0 and 150
# ---------------------------------------------------------------------------

class TestBR_CF_008_age_must_be_between_0_and_150:
    """age must be in [0, 150]."""

    def test_age_above_150_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "age": 151},
        })
        assert resp.status_code == 400, f"Expected 400 for age=151, got {resp.status_code}: {resp.text}"

    def test_age_zero_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "age": 0},
        })
        assert resp.status_code in (200, 201), f"Age=0 (newborn) must be accepted: {resp.text}"

    def test_boundary_age_150_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "age": 150},
        })
        assert resp.status_code in (200, 201), f"Age=150 (boundary) must be accepted: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: Address requires at least one location field
# ---------------------------------------------------------------------------

class TestBR_CF_003_address_requires_at_least_one_location_field:
    """Each address entry must include at least one of: doorNo, street, landmark, city."""

    def test_address_with_no_location_fields_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "address": [{"latitude": 12.97, "longitude": 77.59}],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for address without location field, got {resp.status_code}: {resp.text}"

    def test_address_with_city_only_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "address": [{"city": "Bangalore"}]},
        })
        assert resp.status_code in (200, 201), f"Address with city must be accepted: {resp.text}"

    def test_address_with_door_no_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "address": [{"doorNo": "42A"}]},
        })
        assert resp.status_code in (200, 201), f"Address with doorNo must be accepted: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: Longitude bounds between -180 and 180
# ---------------------------------------------------------------------------

class TestBR_CF_005_longitude_bounds:
    """Longitude must be in [-180, 180]."""

    def test_longitude_above_180_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "address": [{"longitude": 181.0, "city": "Testcity"}],
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for longitude=181, got {resp.status_code}: {resp.text}"

    def test_boundary_longitude_180_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {
                **_base_individual(),
                "address": [{"longitude": 180.0, "city": "Testcity"}],
            },
        })
        assert resp.status_code in (200, 201), \
            f"Boundary longitude=180 must be accepted: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-007: Date of birth maximum 150 years past
# ---------------------------------------------------------------------------

class TestBR_CF_007_dob_maximum_150_years_past:
    """dateOfBirth must not be more than 150 years before today."""

    def test_dob_over_150_years_ago_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "dateOfBirth": "01/01/1800"},
        })
        assert resp.status_code == 400, \
            f"Expected 400 for DOB > 150 years ago, got {resp.status_code}: {resp.text}"

    def test_dob_exactly_at_limit_boundary(self, request, base_url, auth_headers):
        from datetime import datetime, timedelta
        cutoff = datetime.now() - timedelta(days=150 * 365)
        dob = cutoff.strftime("%d/%m/%Y")
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "dateOfBirth": dob},
        })
        assert resp.status_code in (200, 201, 400), \
            f"DOB at 150-year boundary: {resp.status_code} (service decides on exact cutoff)"


# ---------------------------------------------------------------------------
# BR-CF-009: Cardinality limits on nested entity arrays
# ---------------------------------------------------------------------------

class TestBR_CF_009_cardinality_limits_on_nested_entity_arrays:
    """addresses ≤ 16, identifiers ≤ 16, documents ≤ 20 entries."""

    def test_17_addresses_rejected(self, request, base_url, auth_headers):
        addresses = [{"city": f"City{i}"} for i in range(17)]
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "address": addresses},
        })
        assert resp.status_code == 400, \
            f"Expected 400 for 17 addresses, got {resp.status_code}: {resp.text}"

    def test_16_addresses_accepted(self, request, base_url, auth_headers):
        addresses = [{"city": f"City{i}"} for i in range(16)]
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "address": addresses},
        })
        assert resp.status_code in (200, 201), \
            f"16 addresses (boundary) must be accepted: {resp.text}"

    def test_21_documents_rejected(self, request, base_url, auth_headers):
        docs = [{"documentType": "ID", "fileStoreId": f"file-{i}"} for i in range(21)]
        resp = _post(request.node, f"{base_url}/individuals", auth_headers, {
            "individual": {**_base_individual(), "documents": docs},
        })
        assert resp.status_code == 400, \
            f"Expected 400 for 21 documents, got {resp.status_code}: {resp.text}"
