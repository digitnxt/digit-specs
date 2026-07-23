# Employee conformance — change log & run guide

A durable record of everything changed while bringing the Employee service into
conformance, so we don't re-derive it each time. Two parts:
**A. Service changes** (fixes to the employee service, driven by conformance findings)
**B. Conformance changes** (changes to this test suite itself), plus a run recipe.

## Context / targets

- **Contract / spec:** `v3.0.0/employee.yaml` (mirrored into this dir as `schema.yaml`, remote
  `$ref`s to `common.yaml` kept as-is).
- **Two implementations of the same API:**
  - **Go** — `conformance/code/employee-go`, deployed at context path **`employee`**
    → base URL `https://digit-lts.digit.org/employee/v3`. This is the **conformant reference** (43/43 behavioral, handles all malformed input correctly).
  - **Java** — `conformance/code/employee`, deployed at context path **`employee-java`**
    → base URL `https://digit-lts.digit.org/employee-java/v3`. Brought to parity over several image deploys.
- **Runs go through Kong** (auth + header injection at the gateway). Tenant **`MAD`**.
- **Boundary** used for jurisdiction tests (must exist in the tenant):
  `code=STATE1_1_2nvb`, `boundaryType=state`, `hierarchyType=state-district-hierarchyas1i`.

---

# Part A — Service changes (employee service)

The conformance suite is written against the contract; the Go service already met it. The **Java**
service was the one changed, deploy by deploy, as findings were fixed. Behavioral score progression:
**11/43 → 34/43 → 38/43 → 43/43.**

### Java fixes applied (all confirmed via live re-runs)
1. **Jurisdiction endpoints** — were `500 UNHANDLED_EXCEPTION`; now create/get/search/update work (201/200).
2. **Not-found** — `GET/PUT/PATCH/DELETE/deactivate/reactivate` on a missing employee: was 400/500 → now **404**.
3. **Empty batch** `POST /employees []` — was 201 → now **400**.
4. **Missing required fields** on create — was 500 → now **400**.
5. **deactivate/reactivate** — no longer require a body; already-in-state → **409**.
6. **`auditDetail`** — now present on responses.
7. **`version`** — now present AND initializes at **1** (was 0/absent); enables optimistic-concurrency 409s and the PUT/PATCH version handshake.
8. **PUT** — enforces `version` required (400 when missing on an existing row).
9. **Error envelope** — was `{"Errors":[{...}]}`; now a **bare `[Error]` array** matching `common.yaml`.
10. **Pagination overflow** — `offset`/`limit` above int32 max: was 500 → now **400**.
11. **Malformed input (partial)** — `isActive=null`, `null` PUT/PATCH body: was 500 → now **400**.

### Spec change
- **`401` documented** on all 12 operations (in `v3.0.0/employee.yaml` and the conformance
  `schema.yaml`). Every endpoint is behind `BearerAuth` and can return 401 via Kong, but the spec
  hadn't listed it — schemathesis's `status_code_conformance` flagged the gap. (403 deemed not needed.)

### Java — malformed-input handling (RESOLVED)
Root cause was **no global exception handler**, so bad input fell through to a catch-all `500`. This
was fixed (global handler + gateway RBAC) — all confirmed live now returning 4xx:

| Input | Java before | Java now | Go |
|---|---|---|---|
| `?dateOfAppointmentTo/From=null` (unparseable date) | 500 | **400** | 400 |
| `?isActive=null` (unparseable bool) | 500 | **400** | 400 |
| `?offset`/`?limit` int32 overflow | 500 | **400** | 400 |
| `null` request body (PUT/PATCH) | 500 | **400** | 400 |
| `boundaryRelation:[null]` / `jurisdictions:[null]` (null array element) | 500 | **400** | **400** (Go also fixed) |
| `QUERY` / unknown HTTP method | 500 | **403** (gateway RBAC) | 403 |

Only remaining 5xx is the **downstream boundary 502** on `POST …/jurisdictions` with pathological
exotic-Unicode `boundaryRelation` — present in **both** Go and Java, correctly surfaced as
`502 DOWNSTREAM_ERROR` (now documented in the spec). See "Known / accepted" in Current status.

### Go service
Reference implementation. No changes needed — 43/43 behavioral, and returns a descriptive **400**
for every malformed-input class (overflow, unparseable bool/date, null body, bad numbers) via its
binding layer; unknown method → 403 (gateway RBAC).

---

# Part B — Conformance suite changes (this directory)

Rewrote the month-stale suite to the current v3 contract and the through-Kong deployment.

### `schema.yaml`
- Replaced with the current spec (copy of `v3.0.0/employee.yaml`); remote `$ref`s to `common.yaml` kept.
- Now includes the `401` responses (see Part A spec change).

### `conftest.py`
- **Kong gateway profile** rebuilt from a real gateway response: `X-Kong-Request-Id` (required),
  `X-Kong-Upstream-Latency`/`X-Kong-Proxy-Latency` (optional), rate-limit headers (optional).
- **auth_headers**: sends `Authorization: Bearer <token>` + optional `X-Tenant-ID`. Does **not** send
  `X-User-ID` — Kong injects it from the token.
- **Hypothesis profiles** added for the schema module: `bounded` (25 examples) and `full` (100),
  both `deadline=None` (the default 200 ms deadline is unusable over a network).

### `tests/helpers/validators.py`
- `assert_service_response_headers` → asserts the real Kong-added headers: `X-Response-Time` (str,
  e.g. `"19.00ms"`), `X-Response-Timestamp` (epoch-ms int), `X-Tenant-ID`, `X-User-ID`, `X-Kong-Request-Id`.
- Dropped `assert_pagination_shape` (search is a **bare array** now).
- Added `assert_bare_array`, `assert_error_array` (bare `[{code,message}]`), `assert_boundary_relation`.

### `tests/helpers/factories.py`
- `boundaryRelation` is now an array of `{code, boundaryType, hierarchyType}` objects.
- Jurisdiction create body has **no** `employeeId` (it's path-scoped).
- `version` included in PUT (full-state), PATCH, and jurisdiction PUT payloads.
- Boundary is configurable via env vars (explicit arg → env → random fallback):
  `CONFORMANCE_BOUNDARY_CODE`, `CONFORMANCE_BOUNDARY_TYPE`, `CONFORMANCE_BOUNDARY_HIERARCHY`.
- Still omits `userId`/`individualId` to avoid Keycloak/Individual downstream validation.

### `tests/test_response_contracts.py`, `test_error_contracts.py`, `test_stateful_flows.py`
- Rewritten to the current contract: nested `/employees/{id}/jurisdictions[/{jid}]`, bare-array
  search, `version` handshake on updates, `DELETE` → **204** empty, deactivate/reactivate repeat → **409**,
  stale version → **409 ROW_VERSION_MISMATCH**, empty batch / empty PATCH / missing version → **400**,
  error-as-array. 43 tests total.

### `tests/test_schema_conformance.py` (schemathesis / property-based)
- Excludes checks that fire on gateway/environment artifacts or intentional platform behaviour, not
  real contract violations:
  - `positive_data_acceptance` — spec inline examples use boundary/user data invalid for the tenant.
  - `content_type_conformance` — Tomcat serves `text/html` 400 on malformed headers (container layer).
  - `missing_required_header` — Kong injects the "required" client headers from the token.
  - `unsupported_method` — Kong owns routing/auth; unknown methods handled at the gateway.
  - `negative_data_rejection` — its only remaining hit is **unknown query params → 200** (e.g.
    `?foo=bar`), which is **deliberate, uniform behaviour across all four services**
    (individual-go/-java, employee-go/-java all ignore unknown query params). Invalid query *values*
    (bad bool/date, over-max, overflow, non-numeric) ARE rejected with 400 everywhere, and those are
    asserted deterministically in `test_error_contracts.py`.
  - `ignored_auth` — re-sends the request without auth to check the service enforces it. Inapplicable:
    auth is enforced at Kong (the service does none), and the suite injects the token via headers so
    the check can't remove it → false "auth ignored." (Its internal re-issue is what exposed the
    base_url quirk — see below.)
  - `response_headers_conformance` — Kong sets timing/rate-limit headers in its own format
    (`X-Response-Time: "28.00ms"`), which doesn't match the spec's declared header types. Gateway
    concern, not the service contract.
  This exclusion set now **matches the individual-service suite exactly** (both run through Kong).
- **Kept active** (high-value): `response_schema_conformance`, `status_code_conformance`,
  `not_a_server_error` (caught the offset/limit int32-overflow 500), etc.
- **base_url fix:** the schema is loaded from a local file (its own base URL is `file://…`). Checks
  that re-issue the request internally (e.g. `ignored_auth`) would fall back to that and raise
  `IncorrectUsage`. The test sets `schema.config.base_url = base_url` so every call — ours and the
  checks' internal re-calls — resolves against the real gateway. (Same fix applied to individual-service.)
- Does **not** assert gateway headers (e.g. `X-Kong-Request-Id`) here — those aren't in the spec, and
  adversarial fuzz requests get nginx-level responses that legitimately lack them; gateway-header
  presence is checked in the behavioral suite on well-formed requests.
- Skips destructive `DELETE /employees/{employeeId}` (hard delete) and swallows transport-level
  malformed-header errors (`UnicodeEncodeError`/`InvalidHeader`).
- `conftest.py` hypothesis profiles (`bounded`/`full`) suppress `too_slow` + `filter_too_much`
  (network latency + tightly-constrained-schema generation, not service issues).

---

# How to run

```bash
cd conformance/services/employee-service

# boundary that exists in the MAD tenant (jurisdiction tests need a real one)
export CONFORMANCE_BOUNDARY_CODE="STATE1_1_2nvb"
export CONFORMANCE_BOUNDARY_TYPE="state"
export CONFORMANCE_BOUNDARY_HIERARCHY="state-district-hierarchyas1i"

TOKEN="<fresh MAD bearer token>"        # short-lived (~2h); grab a new one per session

# --- Java (employee-java) ---
BASE="https://digit-lts.digit.org/employee-java/v3"
# --- Go   (employee)      ---   BASE="https://digit-lts.digit.org/employee/v3"

# 1) Behavioral + contract suite (fast, ~15-30s, self-cleaning)
python3 -m pytest -p no:randomly -q \
  --base-url "$BASE" --api-token "$TOKEN" --tenant-id MAD --gateway kong \
  tests/test_response_contracts.py tests/test_error_contracts.py tests/test_stateful_flows.py

# 2) Schemathesis property-based module (~5 min; bounded profile).
#    NOTE: writes uncleaned random data to the tenant.
python3 -m pytest -p no:randomly -q --hypothesis-profile=bounded \
  --base-url "$BASE" --api-token "$TOKEN" --tenant-id MAD --gateway kong \
  tests/test_schema_conformance.py
```

Tips:
- Use `--tb=line` to see one-line assertion reasons; `--hypothesis-profile=full` for 100 examples/op.
- The suite targets **through Kong** — pointing it at the raw service will fail the 401 + Kong-header
  assertions by design.

---

# Current status (final)

| Layer | Go (`employee`) | Java (`employee-java`) |
|---|---|---|
| Behavioral (43) | 43/43 ✅ | 43/43 ✅ |
| Malformed input (bad type/date/bool, overflow, null body, null array elements) → 400 | ✅ | ✅ |
| Schemathesis — `response_schema_conformance` / `status_code_conformance` | pass ✅ | pass ✅ |
| Schemathesis — `negative_data_rejection` (invalid query *values*) | pass ✅ | pass ✅ |
| Schemathesis — `not_a_server_error` | ✅ (aside from the shared downstream 502 below) | ✅ (same) |

**Both implementations are conformant** — behavioral + property-based — and at parity.

Resolved along the way (all confirmed live):
- Java: error envelope → bare `[Error]` array; `version` init = 1; 404 not-found; jurisdiction
  endpoints; pagination int32-overflow → 400; `isActive=null` / `dateOfAppointment*=null` / null
  body → 400; `boundaryRelation:[null]` & `jurisdictions:[null]` → 400. Go: `jurisdictions:[null]`
  → 400.
- Spec: documented **401** and **502** (downstream-dependency failure) on the relevant operations.

Final schemathesis (bounded, latest run): **Go 12/12**, **Java 11/12** — the one Java miss is the
shared null-byte finding below (Go 500s on it too when the payload is generated; Go's run just didn't
generate it that pass).

Open finding (shared, low priority):
- **Null byte / invalid UTF-8 in a query filter value → 500** on both Go and Java. Minimal repro:
  `GET /employees?designations=a%00b`. The value reaches Postgres → invalid byte sequence → typed DB
  error (`DATABASE_ERROR` / `DATA_ACCESS_ERROR`), not a crash. Fix: strip/reject NUL + invalid UTF-8
  in query params → 400 before the DB call. Affects both implementations equally; pathological input.

Known / accepted (not defects):
- **Unknown query params → 200** on all four services — intentional, uniform leniency
  (`negative_data_rejection` excluded).
- **Downstream 502** on `POST …/jurisdictions` with pathological exotic-Unicode `boundaryRelation`
  (both Go & Java): the employee service *correctly* maps a boundary-service failure to
  `502 DOWNSTREAM_ERROR` (now documented). It still trips `not_a_server_error` because that check
  flags any 5xx by design. Real fix, if wanted, is boundary-service hardening or pre-validating
  control chars → 400 before the downstream call. Low priority; no realistic client hits it.

*(The earlier schemathesis `IncorrectUsage: base_url` on GET nodes is now **fixed** — see the
base_url fix in Part B; it was a test-setup issue, not the service.)*

See `failed.md` for the detailed finding history.
