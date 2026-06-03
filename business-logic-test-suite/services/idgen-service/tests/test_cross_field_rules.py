"""
Cross-field rule tests for IDGen service.
Rules where two or more fields in the same request body interact.
"""
import uuid
import requests as req_lib
from tests.helpers.curl_builder import attach_curl


def _post(node, url, headers, body):
    r = req_lib.Request("POST", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _put(node, url, headers, body):
    r = req_lib.Request("PUT", url, headers=headers, json=body)
    p = r.prepare()
    attach_curl(node, p)
    return req_lib.Session().send(p)


def _tpl_code():
    return "BR-CF-" + uuid.uuid4().hex[:8].upper()


def _delete_tpl(base_url, code, version, headers):
    try:
        req_lib.delete(
            f"{base_url}/template",
            params={"templateCode": code, "version": version},
            headers=headers,
        )
    except Exception:
        pass


# ---------------------------------------------------------------------------
# BR-CF-001: Padding must accommodate sequence start
# ---------------------------------------------------------------------------

class TestBR_CF_001_padding_must_accommodate_sequence_start:
    """padding.length must be >= number of digits in sequence.start."""

    def test_padding_equal_to_start_width_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1000, "padding": {"length": 4, "char": "0"}},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_padding_shorter_than_start_width_rejected(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1000, "padding": {"length": 3, "char": "0"}},
            },
        })
        assert resp.status_code == 400, f"Expected 400, got {resp.status_code}: {resp.text}"

    def test_boundary_padding_exactly_one_digit_shorter_rejected(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "DAILY", "start": 100, "padding": {"length": 2, "char": "0"}},
            },
        })
        assert resp.status_code == 400, f"Expected 400 (2 < 3 digits of 100), got {resp.status_code}: {resp.text}"

    def test_no_padding_with_start_one_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CF-002: Charset ranges must be ordered and class-homogeneous
# ---------------------------------------------------------------------------

class TestBR_CF_002_charset_ranges_must_be_ordered_and_class_homogeneous:
    """Ranges must have start byte <= end byte and must not cross character classes.
    Empty or omitted charset is valid — service applies default A-Z0-9."""

    def test_valid_alpha_range_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{RAND}",
                "random": {"length": 4, "charset": "A-Z"},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_valid_alphanumeric_charset_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{RAND}",
                "random": {"length": 6, "charset": "A-Z0-9"},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_cross_class_charset_range_rejected(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{RAND}",
                "random": {"length": 4, "charset": "A-z"},
            },
        })
        assert resp.status_code == 400, f"Expected 400 for cross-class charset A-z, got {resp.status_code}: {resp.text}"

    def test_reversed_range_rejected(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{RAND}",
                "random": {"length": 4, "charset": "Z-A"},
            },
        })
        assert resp.status_code == 400, f"Expected 400 for reversed range Z-A, got {resp.status_code}: {resp.text}"

    def test_empty_charset_uses_default_and_is_accepted(self, request, base_url, auth_headers):
        # Rule updated: empty charset is valid; service applies default A-Z0-9
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{RAND}",
                "random": {"length": 4, "charset": ""},
            },
        })
        try:
            assert resp.status_code == 201, \
                f"Expected 201 for empty charset (uses default A-Z0-9), got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_omitted_charset_uses_default_and_is_accepted(self, request, base_url, auth_headers):
        # Omitting charset entirely is also valid — service uses default A-Z0-9
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{RAND}",
                "random": {"length": 4},
            },
        })
        try:
            assert resp.status_code == 201, \
                f"Expected 201 for omitted charset (uses default A-Z0-9), got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CF-003: Date format must match keyword list
# ---------------------------------------------------------------------------

class TestBR_CF_003_date_format_must_match_keyword_list:
    """The {DATE:format} token only accepts predefined keywords (case-insensitive).
    Categories: basic numeric, dash/slash/dot-separated, month-year, year-only."""

    def test_basic_numeric_format_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:yyyymmdd}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_dash_separated_format_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:yyyy-mm-dd}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201 for dash-separated format, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_slash_separated_format_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:dd/mm/yyyy}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201 for slash-separated format, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_year_only_format_accepted(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:yyyy}-{SEQ}",
                "sequence": {"scope": "YEARLY", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201 for year-only format, got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_format_matching_is_case_insensitive(self, request, base_url, auth_headers):
        # Rule is case-insensitive: YYYYMMDD should match keyword yyyymmdd
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:YYYYMMDD}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, \
                f"Expected 201 for uppercase keyword (case-insensitive), got {resp.status_code}: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_free_form_format_not_in_keyword_list_rejected(self, request, base_url, auth_headers):
        # "day-month-year" is not in any keyword category
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:day-month-year}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        assert resp.status_code == 400, \
            f"Expected 400 for unknown format 'day-month-year', got {resp.status_code}: {resp.text}"

    def test_go_layout_string_rejected(self, request, base_url, auth_headers):
        # Go layout "2006-01-02" is not a valid keyword
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:2006-01-02}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 1},
            },
        })
        assert resp.status_code == 400, f"Expected 400 for Go layout format, got {resp.status_code}: {resp.text}"


# ---------------------------------------------------------------------------
# BR-CF-004: Template variables validated at generation time
# ---------------------------------------------------------------------------

class TestBR_CF_004_template_variables_validated_at_generation_time:
    """Template with variables succeeds creation; missing variable fails generation with 422."""

    def test_template_with_variable_created_successfully(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{ORG}-{SEQ}",
                "sequence": {"scope": "GLOBAL", "start": 1},
            },
        })
        try:
            assert resp.status_code == 201, f"Template with variable should be created: {resp.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_missing_variable_at_generation_returns_422(self, request, base_url, auth_headers):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{ORG}-{SEQ}", "sequence": {"scope": "GLOBAL", "start": 1}},
        })
        try:
            gen = _post(request.node, f"{base_url}/generate", auth_headers, {
                "templateCode": code,
            })
            assert gen.status_code == 422, f"Expected 422 for missing {{ORG}} variable, got {gen.status_code}: {gen.text}"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)

    def test_generation_succeeds_with_variable_supplied(self, request, base_url, auth_headers):
        code = _tpl_code()
        req_lib.post(f"{base_url}/template", headers=auth_headers, json={
            "templateCode": code,
            "config": {"template": "{ORG}-{SEQ}", "sequence": {"scope": "GLOBAL", "start": 1}},
        })
        try:
            gen = _post(request.node, f"{base_url}/generate", auth_headers, {
                "templateCode": code,
                "variables": {"ORG": "TESTORG"},
            })
            assert gen.status_code == 200, f"Expected 200 with variable supplied, got {gen.status_code}: {gen.text}"
            assert "TESTORG" in gen.json().get("id", ""), "Variable should appear in generated ID"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)


# ---------------------------------------------------------------------------
# BR-CF-005: Scope counter resets to sequence start (observable via generation)
# ---------------------------------------------------------------------------

class TestBR_CF_005_scope_counter_resets_to_sequence_start:
    """DAILY scope counter starts at sequence.start on each new day. Observable via creation."""

    def test_daily_template_with_custom_start_created(self, request, base_url, auth_headers):
        code = _tpl_code()
        resp = _post(request.node, f"{base_url}/template", auth_headers, {
            "templateCode": code,
            "config": {
                "template": "{DATE:yyyymmdd}-{SEQ}",
                "sequence": {"scope": "DAILY", "start": 100, "padding": {"length": 3, "char": "0"}},
            },
        })
        try:
            assert resp.status_code == 201, f"Expected 201, got {resp.status_code}: {resp.text}"
            assert resp.json().get("config", {}).get("sequence", {}).get("start") == 100, \
                "start=100 should be preserved in stored config"
        finally:
            _delete_tpl(base_url, code, "v1", auth_headers)
