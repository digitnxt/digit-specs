"""
Test data factories for the localization service.

All factories produce unique data per call via uuid-based suffixes so
tests running in the same tenant do not collide.
"""

import uuid

LOCALES = ["en_IN", "hi_IN", "mr_IN", "ta_IN", "te_IN"]


def _uid():
    return uuid.uuid4().hex[:8].upper()


def make_msg_code():
    return f"MSG_{_uid()}"


def make_module():
    return f"module-{uuid.uuid4().hex[:8]}"


def make_message(module=None, locale="en_IN", code=None, **overrides):
    """Single Message object."""
    base = {
        "module":  module or make_module(),
        "locale":  locale,
        "code":    code or make_msg_code(),
        "message": f"Test message {_uid()}",
    }
    return {**base, **overrides}


def make_create_request(module=None, locale="en_IN", codes=None, count=1):
    """Valid CreateMessagesRequest body."""
    m = module or make_module()
    if codes:
        msgs = [make_message(module=m, locale=locale, code=c) for c in codes]
    else:
        msgs = [make_message(module=m, locale=locale) for _ in range(count)]
    return {"messages": msgs}


def make_upsert_request(module=None, locale="en_IN", codes=None, count=1):
    """Valid UpsertMessagesRequest body — same shape as create."""
    return make_create_request(module=module, locale=locale, codes=codes, count=count)


def make_update_request(record_uuid, locale, module, message_updates):
    """
    Valid UpdateMessagesRequest body.
    message_updates: list of dicts with keys: uuid, code, message
    """
    return {
        "uuid":     record_uuid,
        "locale":   locale,
        "module":   module,
        "messages": message_updates,
    }


def make_find_missing_request(locales=None):
    """FindMissingMessagesRequest — empty body checks all locales."""
    if locales:
        return {"locales": locales}
    return {}


# ── Invalid payloads ───────────────────────────────────────────────────────

def make_invalid_create_request(strategy="empty_messages"):
    strategies = {
        "empty_messages":    {"messages": []},
        "missing_messages":  {},
        "null_messages":     {"messages": None},
        "wrong_type":        {"messages": "not-an-array"},
        "missing_locale":    {"messages": [{"module": "m", "code": "C", "message": "T"}]},
        "missing_code":      {"messages": [{"module": "m", "locale": "en_IN", "message": "T"}]},
        "missing_module":    {"messages": [{"locale": "en_IN", "code": "C", "message": "T"}]},
    }
    return strategies.get(strategy, {})


def make_invalid_update_request(strategy="missing_messages"):
    strategies = {
        "missing_messages":   {"uuid": "some-uuid", "locale": "en_IN", "module": "m"},
        "empty_messages":     {"uuid": "some-uuid", "locale": "en_IN", "module": "m", "messages": []},
        "missing_uuid":       {"locale": "en_IN", "module": "m", "messages": []},
    }
    return strategies.get(strategy, {})
