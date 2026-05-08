"""
Test data factories for the url-shortener service.
"""

import time
import uuid


def _uid():
    return uuid.uuid4().hex[:8]


def make_shorten_request(url=None, **overrides):
    """Valid ShortenRequest. Required: url (valid URI)."""
    base = {"url": url or f"https://example.com/conformance-test/{_uid()}?param={_uid()}"}
    return {**base, **overrides}


def make_shorten_request_with_validity(valid_for_seconds=3600):
    """ShortenRequest with an explicit validity window."""
    now_ms = int(time.time() * 1000)
    return {
        "url": f"https://example.com/valid/{_uid()}",
        "validFrom": now_ms,
        "validTill": now_ms + valid_for_seconds * 1000,
    }


def make_shorten_request_already_expired():
    """ShortenRequest where validTill is in the past — redirect should 404."""
    now_ms = int(time.time() * 1000)
    return {
        "url": f"https://example.com/expired/{_uid()}",
        "validFrom": now_ms - 10000,
        "validTill": now_ms - 1000,
    }


def make_shorten_request_future_validity(delay_seconds=3600):
    """ShortenRequest where validFrom is in the future — not yet active."""
    now_ms = int(time.time() * 1000)
    return {
        "url": f"https://example.com/future/{_uid()}",
        "validFrom": now_ms + delay_seconds * 1000,
        "validTill": now_ms + (delay_seconds + 3600) * 1000,
    }


def extract_key_from_short_url(short_url: str) -> str:
    """Extract the short key from a full short URL like http://host/aB3x → 'aB3x'."""
    return short_url.rstrip("/").split("/")[-1]


# ── Invalid payloads ───────────────────────────────────────────────────────

def make_invalid_shorten_request(strategy="missing_url"):
    strategies = {
        "missing_url":       {},
        "empty_url":         {"url": ""},
        "invalid_url":       {"url": "not-a-valid-url"},
        "null_url":          {"url": None},
        "url_too_long":      {"url": "https://example.com/" + "x" * 2050},
        "wrong_type":        {"url": 12345},
        "negative_valid_from": {"url": "https://example.com/test", "validFrom": -1},
        "negative_valid_till": {"url": "https://example.com/test", "validTill": -1},
    }
    return strategies.get(strategy, {})
