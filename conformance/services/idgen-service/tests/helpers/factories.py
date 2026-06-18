import uuid


def _tpl_code():
    """Valid template code: uppercase, 2-64 chars."""
    return "TPL-" + uuid.uuid4().hex[:8].upper()


# ── Template requests ─────────────────────────────────────────────────────────

def make_template(code=None, **overrides):
    """Minimal valid IDGenTemplateRequest with a plain SEQ template."""
    base = {
        "templateCode": code or _tpl_code(),
        "config": {
            "template": "{SEQ}",
            "sequence": {
                "scope": "GLOBAL",
                "start": 1,
                "padding": {"length": 4, "char": "0"},
            },
        },
    }
    return {**base, **overrides}


def make_template_dated(code=None):
    """Template with DATE and SEQ tokens, DAILY scope."""
    return {
        "templateCode": code or _tpl_code(),
        "config": {
            "template": "{DATE:yyyymmdd}-{SEQ}",
            "sequence": {
                "scope": "DAILY",
                "start": 1,
                "padding": {"length": 4, "char": "0"},
            },
        },
    }


def make_template_with_variable(code=None):
    """Template requiring a {ORG} variable at generation time."""
    return {
        "templateCode": code or _tpl_code(),
        "config": {
            "template": "{ORG}-{DATE:yyyy}-{SEQ}",
            "sequence": {
                "scope": "YEARLY",
                "start": 1,
                "padding": {"length": 6, "char": "0"},
            },
        },
    }


def make_template_with_rand(code=None):
    """Template with RAND token."""
    return {
        "templateCode": code or _tpl_code(),
        "config": {
            "template": "{SEQ}-{RAND}",
            "sequence": {"scope": "GLOBAL", "start": 1},
            "random": {"length": 4, "charset": "A-Z0-9"},
        },
    }


def make_template_update(code, **overrides):
    """Updated template config for PUT — creates v2."""
    base = {
        "templateCode": code,
        "config": {
            "template": "{DATE:yyyymmdd}-{SEQ}-{RAND}",
            "sequence": {
                "scope": "DAILY",
                "start": 1,
                "padding": {"length": 5, "char": "0"},
            },
            "random": {"length": 2, "charset": "A-Z"},
        },
    }
    return {**base, **overrides}


def make_invalid_template(strategy="missing_required"):
    strategies = {
        "missing_required":          {},
        "missing_template_code":     {"config": {"template": "{SEQ}"}},
        "missing_config":            {"templateCode": _tpl_code()},
        "template_code_too_short":   {"templateCode": "X", "config": {"template": "{SEQ}"}},
        "invalid_sequence_scope":    {
            "templateCode": _tpl_code(),
            "config": {"template": "{SEQ}", "sequence": {"scope": "INVALID"}},
        },
        "invalid_charset_cross_class": {
            "templateCode": _tpl_code(),
            "config": {"template": "{RAND}", "random": {"charset": "A-z"}},
        },
        "padding_start_overflow": {
            "templateCode": _tpl_code(),
            "config": {
                "template": "{SEQ}",
                "sequence": {"start": 99999, "padding": {"length": 4, "char": "0"}},
            },
        },
    }
    return strategies.get(strategy, {})


# ── Generate requests ─────────────────────────────────────────────────────────

def make_generate_request(template_code, variables=None):
    """Valid GenerateIDRequest."""
    base = {"templateCode": template_code}
    if variables:
        base["variables"] = variables
    return base


def make_bulk_generate_request(template_code, count=5, variables=None):
    """Valid BulkGenerateIDRequest."""
    base = {"templateCode": template_code, "count": count}
    if variables:
        base["variables"] = variables
    return base


def make_invalid_generate(strategy="missing_required"):
    strategies = {
        "missing_required":        {},
        "template_code_too_short": {"templateCode": "X"},
    }
    return strategies.get(strategy, {})


def make_invalid_bulk_generate(strategy="missing_required"):
    strategies = {
        "missing_required":  {},
        "missing_count":     {"templateCode": "some-template"},
        "zero_count":        {"templateCode": "some-template", "count": 0},
        "excess_count":      {"templateCode": "some-template", "count": 1001},
    }
    return strategies.get(strategy, {})
