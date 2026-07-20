"""
Test data factories for the Individual Service.

All factories produce unique data per call via uuid-based suffixes so tests
running in the same tenant do not collide. Server-managed fields
(`id`, `individualId`, `tenantId`, `isActive`, `version`, `auditDetail`)
are never set by these factories — they are server-stamped.

Naming aligned to the new spec:
- `givenName` and `gender` are mandatory on create.
- At least one of `mobileNumber` or `email` must be provided.
- `gender` enum is MALE / FEMALE / OTHER (no PREFER_NOT_TO_SAY).
"""

import secrets
import string
import uuid

# Letters-only suffix — the service's default `alphaOnly` regex
# (^[a-zA-Z\s]+$) rejects digits in givenName / familyName. Using hex
# UIDs in names produces 400s on every create. Use letters-only suffixes
# for any field that flows into name validation.
_NAME_ALPHABET = string.ascii_letters


def _uid():
    """Letters-only 8-char unique suffix for name fields. Safe under the
    platform default `alphaOnly` regex (^[a-zA-Z\\s]+$)."""
    return "".join(secrets.choice(_NAME_ALPHABET) for _ in range(8))


def _uid_alnum():
    """Mixed alphanumeric suffix for fields with no alphabet-only constraint
    (e.g. fileStoreId, documentUid, userId)."""
    return uuid.uuid4().hex[:8].lower()


def _unique_mobile():
    """Generate a 10-digit mobile number starting with 6-9. Matches both
    the platform baseline (`^[0-9]{6,15}$`) and the example tenant
    `mobileRegex: ^[6-9][0-9]{9}$`."""
    return "9" + str(uuid.uuid4().int)[:9]


def _unique_email():
    return f"conformance-{_uid_alnum()}@example.com"


# ── Individual factories ──────────────────────────────────────────────────────

def make_individual(**overrides):
    """Minimal valid Individual payload — givenName + gender + mobileNumber.

    Per spec: givenName and gender are required; at least one of mobileNumber
    or email must be provided.
    """
    base = {
        "givenName": f"Conformance {_uid()}",
        "gender": "MALE",
        "mobileNumber": _unique_mobile(),
    }
    base.update(overrides)
    return base


def make_individual_full(**overrides):
    """Individual with most optional fields populated. Useful for shape tests."""
    base = make_individual()
    base.update({
        "familyName": "Test",
        "otherNames": "C",
        "dateOfBirth": "1990-05-12",
        "email": _unique_email(),
        "mobileNumberVerified": False,
        "emailVerified": False,
        "locale": "en-IN",
        "fatherName": "Conformance Father",
        "userId": f"usr-{_uid().lower()}",
        "additionalAttributes": {
            "occupation": "engineer",
            "preferredLanguage": "en",
        },
    })
    base.update(overrides)
    return base


def make_individual_with_address(**overrides):
    """Individual with one valid address entry.

    Per spec, each address must include at least one of doorNo/street/landmark/city.
    """
    base = make_individual()
    base["address"] = [
        {
            "type": "PERMANENT",
            "doorNo": "12-A",
            "buildingName": "Lotus Apartments",
            "street": "MG Road",
            "landmark": "Near Central Park",
            "city": "Bengaluru",
            "region": "Karnataka",
            "country": "India",
            "pincode": "560001",
            "latitude": 12.9716,
            "longitude": 77.5946,
        }
    ]
    base.update(overrides)
    return base


def make_individual_with_identifiers(**overrides):
    """Individual with one AADHAAR identifier.

    Per spec, each identifierType may appear at most once per individual;
    identifierType must be one of the documented enum values.
    """
    base = make_individual()
    base["identifiers"] = [
        {
            "identifierType": "AADHAAR",
            "identifierId": str(uuid.uuid4().int)[:12],
            "verified": False,
        }
    ]
    base.update(overrides)
    return base


def make_individual_with_documents(**overrides):
    """Individual with one general document.

    Per spec, Document requires documentType (minLength 2) and fileStoreId.
    """
    base = make_individual()
    base["documents"] = [
        {
            "documentType": "PROOF_OF_RESIDENCE",
            "fileStoreId": f"fs-{_uid().lower()}",
            "documentUid": f"DOC-{_uid()}",
        }
    ]
    base.update(overrides)
    return base


def make_individual_update(**overrides):
    """Valid update payload (PUT — full replace).

    Per spec, id/individualId/tenantId in the body are ignored on update;
    additionalAttributes is replaced in full (not merged).
    """
    base = {
        "givenName": f"Updated {_uid()}",
        "familyName": "Updated",
        "gender": "FEMALE",
        "mobileNumber": _unique_mobile(),
        "email": _unique_email(),
    }
    base.update(overrides)
    return base


# ── Invalid Individual payloads ───────────────────────────────────────────────

def make_invalid_individual(strategy="missing_given_name"):
    """Invalid Individual payloads. Each strategy violates one constraint."""
    strategies = {
        # Required fields
        "missing_given_name": {
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
        },
        "missing_gender": {
            "givenName": f"Test {_uid()}",
            "mobileNumber": _unique_mobile(),
        },
        "missing_mobile_and_email": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
        },
        "empty_given_name": {
            "givenName": "",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
        },
        "given_name_too_long": {
            "givenName": "x" * 129,
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
        },
        # Enum violations
        "invalid_gender": {
            "givenName": f"Test {_uid()}",
            "gender": "UNKNOWN",
            "mobileNumber": _unique_mobile(),
        },
        "lowercase_gender": {
            "givenName": f"Test {_uid()}",
            "gender": "male",  # must be uppercase enum
            "mobileNumber": _unique_mobile(),
        },
        # Format violations
        "invalid_email": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "email": "not-an-email",
        },
        "invalid_dob_format": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
            "dateOfBirth": "01-01-1990",  # wrong format, must be YYYY-MM-DD
        },
        # Identifier constraints
        "duplicate_identifier_type": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
            "identifiers": [
                {"identifierType": "AADHAAR", "identifierId": "111111111111"},
                {"identifierType": "AADHAAR", "identifierId": "222222222222"},  # duplicate type
            ],
        },
        "invalid_identifier_type": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
            "identifiers": [
                {"identifierType": "MADE_UP_ID", "identifierId": "12345"},
            ],
        },
        # Address constraints
        "empty_address_entry": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
            "address": [{"type": "PERMANENT"}],  # no doorNo/street/landmark/city
        },
        "latitude_out_of_range": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
            "address": [
                {"type": "PERMANENT", "city": "Bengaluru", "latitude": 200.0},
            ],
        },
        # additionalAttributes constraints
        "invalid_attribute_key": {
            "givenName": f"Test {_uid()}",
            "gender": "MALE",
            "mobileNumber": _unique_mobile(),
            "additionalAttributes": {"has space": "value"},  # space not allowed
        },
        # Type violations
        "wrong_types": {
            "givenName": 12345,
            "gender": "MALE",
            "mobileNumber": True,
            "dateOfBirth": 19900512,
        },
    }
    return strategies.get(strategy, {})


# ── Config factories ──────────────────────────────────────────────────────────

def make_config_request(**overrides):
    """Valid ConfigUpsertRequest — all fields are optional per spec.

    Default mobileRegex matches the factory's `_unique_mobile()` format.
    """
    base = {
        "mobileRegex": r"^[6-9][0-9]{9}$",
        "nameRegex": r"^[A-Za-z0-9 ]+$",
        "uniquenessCriteria": ["mobileNumber"],
    }
    base.update(overrides)
    return base


def make_empty_config_request():
    """Valid empty ConfigUpsertRequest — all fields optional, empty config is allowed."""
    return {}


def make_invalid_config_request(strategy="invalid_mobile_regex"):
    """Invalid config payloads. Each strategy violates one constraint."""
    strategies = {
        # Invalid regex — server rejects with 400
        "invalid_mobile_regex": {"mobileRegex": "[unclosed("},
        "invalid_name_regex": {"nameRegex": "[unclosed("},
        # Wrong types
        "wrong_uniqueness_type": {"uniquenessCriteria": "mobileNumber"},  # should be array
        "wrong_regex_type": {"mobileRegex": 12345},  # should be string
        "mobile_regex_too_long": {"mobileRegex": "x" * 513},  # maxLength 512
    }
    return strategies.get(strategy, {})
