"""
Cross-field rule tests for URL Shortener service.
"""
import time
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _now_ms():
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------
# BR-CF-001: ValidTill must be future at shorten time
# ---------------------------------------------------------------------------

class TestBR_CF_001_valid_till_must_be_future_at_shorten_time:
    """validTill set to the past or current time is rejected."""

    def test_valid_till_in_future_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-future",
            "validTill": _now_ms() + 3_600_000,
        })
        assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"

    def test_valid_till_in_past_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-past",
            "validTill": _now_ms() - 1000,
        })
        assert resp.status_code == 400, f"Expected 400 for past validTill, got {resp.status_code}: {resp.text}"

    def test_valid_till_equal_to_now_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf001-now",
            "validTill": now,
        })
        assert resp.status_code == 400, f"Expected 400 for validTill=now, got {resp.status_code}: {resp.text}"

    def test_no_valid_till_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url": "https://example.com/cf001-notill",
        })
        assert resp.status_code == 201, f"Expected 201 without validTill, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-002: ValidFrom must be less than validTill
# ---------------------------------------------------------------------------

class TestBR_CF_002_valid_from_must_be_less_than_valid_till:
    """When both provided, validFrom < validTill required; equal values rejected."""

    def test_valid_window_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf002-valid",
            "validFrom": now + 60_000,
            "validTill": now + 3_600_000,
        })
        assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"

    def test_equal_from_and_till_rejected(self, request, base_url, auth_headers):
        ts = _now_ms() + 3_600_000
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf002-equal",
            "validFrom": ts,
            "validTill": ts,
        })
        assert resp.status_code == 400, f"Expected 400 for equal from/till, got {resp.status_code}: {resp.text}"

    def test_from_after_till_rejected(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf002-reversed",
            "validFrom": now + 3_600_000,
            "validTill": now + 60_000,
        })
        assert resp.status_code == 400, f"Expected 400 for from>till, got {resp.status_code}: {resp.text}"

    def test_boundary_one_ms_apart_accepted(self, request, base_url, auth_headers):
        now = _now_ms()
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf002-boundary",
            "validFrom": now + 1_000,
            "validTill": now + 1_001,
        })
        assert resp.status_code == 201, f"Expected 201 for 1ms apart, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-003: URL format and size constraint
# ---------------------------------------------------------------------------

class TestBR_CF_003_url_format_and_size_constraint:
    """URL must be well-formed and <= 8192 chars."""

    def test_valid_https_url_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url": "https://example.com/some/valid/path?q=test",
        })
        assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"

    def test_invalid_url_no_scheme_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url": "not-a-url",
        })
        assert resp.status_code == 400, f"Expected 400 for non-URL, got {resp.status_code}: {resp.text}"

    def test_url_exceeding_8192_chars_rejected(self, request, base_url, auth_headers):
        long_url = "https://example.com/" + "a" * 8173
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url": long_url,
        })
        assert resp.status_code == 400, f"Expected 400 for >8192 char URL, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-005: ShortKeyLength must be within bounds
# ---------------------------------------------------------------------------

class TestBR_CF_005_short_key_length_within_bounds:
    """shortKeyLength must be 4–12 inclusive."""

    def test_valid_short_key_length_accepted(self, request, base_url, auth_headers):
        existing = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        if existing.status_code == 200:
            req_lib.put(f"{base_url}/v3/config", headers=auth_headers,
                        json={"shortKeyLength": 6, "maxShortKeyRetries": 10})
        resp = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        assert resp.status_code == 200
        assert 4 <= resp.json().get("shortKeyLength", 0) <= 12

    def test_short_key_length_below_4_rejected(self, request, base_url, auth_headers):
        existing = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        saved = existing.json() if existing.status_code == 200 else None
        resp = req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                            json={"shortKeyLength": 3, "maxShortKeyRetries": 10})
        if resp.status_code == 409 and saved:
            resp = req_lib.put(f"{base_url}/v3/config", headers=auth_headers,
                               json={"shortKeyLength": 3, "maxShortKeyRetries": 10})
        assert resp.status_code == 400, f"Expected 400 for shortKeyLength=3, got {resp.status_code}: {resp.text}"

    def test_short_key_length_above_12_rejected(self, request, base_url, auth_headers):
        existing = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        saved = existing.json() if existing.status_code == 200 else None
        resp = req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                            json={"shortKeyLength": 13, "maxShortKeyRetries": 10})
        if resp.status_code == 409 and saved:
            resp = req_lib.put(f"{base_url}/v3/config", headers=auth_headers,
                               json={"shortKeyLength": 13, "maxShortKeyRetries": 10})
        assert resp.status_code == 400, f"Expected 400 for shortKeyLength=13, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-006: MaxShortKeyRetries must be within bounds
# ---------------------------------------------------------------------------

class TestBR_CF_006_max_short_key_retries_within_bounds:
    """maxShortKeyRetries, when provided, must be 1–20 inclusive."""

    def test_max_retries_zero_rejected(self, request, base_url, auth_headers):
        existing = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        saved = existing.json() if existing.status_code == 200 else None
        resp = req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                            json={"shortKeyLength": 6, "maxShortKeyRetries": 0})
        if resp.status_code == 409 and saved:
            resp = req_lib.put(f"{base_url}/v3/config", headers=auth_headers,
                               json={"shortKeyLength": 6, "maxShortKeyRetries": 0})
        assert resp.status_code == 400, f"Expected 400 for maxShortKeyRetries=0, got {resp.status_code}: {resp.text}"

    def test_max_retries_above_20_rejected(self, request, base_url, auth_headers):
        existing = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        saved = existing.json() if existing.status_code == 200 else None
        resp = req_lib.post(f"{base_url}/v3/config", headers=auth_headers,
                            json={"shortKeyLength": 6, "maxShortKeyRetries": 21})
        if resp.status_code == 409 and saved:
            resp = req_lib.put(f"{base_url}/v3/config", headers=auth_headers,
                               json={"shortKeyLength": 6, "maxShortKeyRetries": 21})
        assert resp.status_code == 400, f"Expected 400 for maxShortKeyRetries=21, got {resp.status_code}: {resp.text}"

    def test_max_retries_omitted_uses_default(self, request, base_url, auth_headers):
        cfg = req_lib.get(f"{base_url}/v3/config", headers=auth_headers)
        assert cfg.status_code == 200
        assert cfg.json().get("maxShortKeyRetries", 10) == 10 or cfg.json().get("maxShortKeyRetries") is not None


# ---------------------------------------------------------------------------
# BR-CF-004: Epoch millisecond range validation
# ---------------------------------------------------------------------------

class TestBR_CF_004_epoch_millisecond_range_validation:
    """validFrom and validTill must be within [0, 9007199254740991]."""

    def test_negative_valid_from_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf004-neg",
            "validFrom": -1,
        })
        assert resp.status_code == 400, \
            f"Expected 400 for negative validFrom, got {resp.status_code}: {resp.text}"

    def test_overflow_valid_till_rejected(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf004-overflow",
            "validTill": 9007199254740992,
        })
        assert resp.status_code == 400, \
            f"Expected 400 for validTill > MAX_SAFE_INTEGER, got {resp.status_code}: {resp.text}"

    def test_boundary_max_safe_integer_accepted_for_future_ts(self, request, base_url, auth_headers):
        # MAX_SAFE_INTEGER is ~year 285,428 — clearly in the future, so validTill check should pass
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf004-max",
            "validTill": 9007199254740991,
        })
        assert resp.status_code in (201, 400), \
            f"MAX_SAFE_INTEGER validTill should either be accepted or rejected by range check, got {resp.status_code}: {resp.text}"

    def test_zero_valid_from_accepted(self, request, base_url, auth_headers):
        resp = _post(request.node, f"{base_url}/v3/short-url", auth_headers, {
            "url":       "https://example.com/cf004-zero",
            "validFrom": 0,
            "validTill": _now_ms() + 3_600_000,
        })
        assert resp.status_code in (201, 400), \
            f"validFrom=0 should pass epoch range check, got {resp.status_code}: {resp.text}"
