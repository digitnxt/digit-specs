# Employee conformance — uat-saas / tenant P2 (2026-09-29)

Target `https://uat-saas.digit.org/employee/v3` through Kong, image `anishegov/employee:master-bf8e4d5a`
(single implementation, context `/employee`). Seed data from `../p2-seed/seed_p2.py`.

| Layer | Result |
|---|---|
| Behavioral (response / error / stateful) | **39 passed, 4 skipped** |
| New: onboard + `userIds` (`tests/test_onboard_contracts.py`) | **11 / 11** |
| Schemathesis (bounded) | 6 / 13 ops pass — all 7 failures are one gateway cause (F1) |

## Findings

**F1 — Kong `keycloak-rbac`: unknown HTTP method → 500 (gateway defect, affects every service)**
`QUERY` (or any method that isn't a Keycloak scope) → Keycloak `400 invalid_scope` → plugin falls
through to fail-closed `500 RBAC.AuthorizationUnavailable` (`code/kong/plugins/keycloak-rbac/handler.lua:275`).
On digit-lts the same request returned 403. Fix: map `invalid_scope` to 405/403 like `invalid_resource` → 404.
```
curl -X QUERY 'https://uat-saas.digit.org/employee/v3/employees' -H 'Authorization: Bearer <token>' -H 'X-Tenant-ID: P2'
```

**F2 — Boundary service: cannot create boundaries in tenant schemas (blocks 4 jurisdiction tests)**
`POST /boundary/v3/boundaries` → `400 bad SQL grammar`. With `search_path="P2"` PostGIS
(installed in `public`) isn't resolvable: `function st_geomfromgeojson(jsonb) does not exist`.
Verified read-only in psql (works with `search_path "P2", public`). Fix: schema-qualify
`public.ST_SetSRID(public.ST_GeomFromGeoJSON(...))` in `code/boundary/.../BoundaryRepository.java:34`
or include `public` in the tenant search_path. Skipped until fixed:
`test_response_contracts.py:149`, `test_error_contracts.py:206`, `test_stateful_flows.py:151,202`.

**O1 — empty filter value is silently dropped → unfiltered result** (open, design decision)
`GET /employees?userIds=` → Spring binds `""` to an empty `List`, the blank-check loop
(`EmployeeController.java:131`) has nothing to reject, and the repository skips the `IN` clause
when the list is empty (`EmployeeRepository.java:241`) → **same result as no filter at all**. This is
deliberate per the code comment (`EmployeeController.java:128`). Blank entries inside a list
(`userIds=,` / `userIds=%20`) → 400 as the spec says. Individual behaves the same: `?userId=`
returned `totalCount=3` (all of P2), identical to no filter.
Risk: a client that builds `?userIds=${id}` with an unset id gets the full (RBAC-permitted) list
instead of none. Choose one: reject a present-but-empty param with 400 (check
`request.getParameterValues`), or document "empty value = filter ignored" in both specs.

**F1 update** — confirmed by design: `realm_config.json` defines only `get/post/put/patch/delete`
scopes, so `QUERY` is correctly denied. Only the status is off: Keycloak answers `400 invalid_scope`,
which the plugin maps to 500 (`TRACE` already gets 405 before the plugin).

## Environment notes (not defects)
- Seeded idgen `EmployeeCode` must **not** use `{ORG}` — the employee client sends no variables
  (`IdGenClient.java:56`). Template in P2 is `EMP-{DATE:yyyy}-{SEQ}` (v2).
- Onboard creates Keycloak users in realm P2; tests delete employee → individual → Keycloak user.
  Schemathesis may leave random records in P2 (accepted).

---

# Employee conformance — Java (`/employee-java/v3`) vs Go (`/employee/v3`)

Live run through Kong, tenant **MAD**, boundary `STATE1_1_2nvb / state / state-district-hierarchyas1i`.

## Behavioral + contract suite (43 tests)

| Target | Result |
|---|---|
| Go (reference) | **43 / 43** |
| Java — image 1 | 11 / 43 |
| Java — image 2 | 34 / 43 |
| Java — image 3 | 38 / 43 |
| **Java — image 4 (current)** | **43 / 43 ✅** |

All previously-found gaps are now fixed on Java:
- Jurisdiction endpoints (were 500) ✅
- Not-found → 404 (was 400/500) ✅
- Empty batch / missing-required → 400 ✅
- deactivate / reactivate ✅
- `auditDetail` present ✅
- `version` initializes at 1 (was 0) ✅
- PUT validation order (version-required) ✅
- **Error body is now a bare `[Error]` array** (was `{"Errors":[...]}`) ✅

**Java is now fully behaviorally conformant — matches the Go reference.**

## Remaining: schemathesis property-based module only

`test_schema_conformance.py` still reports failures on both Go and Java, from **one env-driven
cause**, not a service defect:

- **`RejectedPositiveData`** — schemathesis replays the OpenAPI's inline `examples`, which contain
  fictional data invalid for the MAD tenant (e.g. `boundaryRelation: STATE33d / state-district-city`,
  `userId: 0e76…`, `individualId: 8c8c…`). Both services correctly reject these with 400, so the
  "API rejected schema-compliant request" check trips. The schema can't know MAD's valid codes.
  (The earlier Java `JsonSchemaError: response not an array` sub-exception is gone now that errors
  are bare arrays.)

### Schemathesis outcome (bounded, through Kong, Java)

Core conformance checks now **pass**:
- `response_schema_conformance` ✅ (response bodies match the schema — after the error-envelope fix)
- `status_code_conformance` ✅ (after 401 was documented in the spec)

**Real bug schemathesis found (worth fixing) — systemic: Java 500s on malformed input.**
Once the gateway/env checks were excluded, `not_a_server_error` surfaced a single systemic gap
across ALL endpoints: the Java service has no global handler for bad input, so a wide class of
malformed requests fall through to an unhandled **500 `UNHANDLED_EXCEPTION`** where Go returns a
proper 4xx. Confirmed Java-vs-Go (live):

| Malformed input | Java | Go |
|---|---|---|
| `?offset=2147483648` (> int32 max) | ~~500~~ → **400 FIXED** | 400 |
| `?limit=99999999999` | ~~500~~ → **400 FIXED** | 400 |
| `?limit=101` (> max 100) | 400 | 400 |
| `?isActive=null` (unparseable bool) | **500** (still) | 400 |
| `PUT` body = `null` | **500** (still) | 400 |
| `PATCH` body = `null` | **500** (still) | 400 |
| `QUERY` HTTP method | 500 | 403 (RBAC) |

`limit`/`offset` were patched per-field. The remaining cases share the same root cause (no global
bad-input handler); a `@ControllerAdvice` mapping `MethodArgumentTypeMismatchException` /
`HttpMessageNotReadableException` / bind exceptions → 400 would clear all of them at once.

### Update after 2nd Java fix (limit/offset/isActive/PUT/PATCH patched)
Confirmed fixed → now 400: `isActive=null`, `offset`/`limit` overflow, `null` PUT/PATCH body.
Behavioral suite still **43/43**. BUT schemathesis still 500s on all 12 ops — same root class via
fields that weren't individually patched:

| Input | Java | Go |
|---|---|---|
| `?isActive=null` (bool) | 400 (fixed) | 400 |
| `?dateOfAppointmentTo=null` (date) | **500** | 400 |
| `?dateOfAppointmentFrom=null` (date) | **500** | 400 |
| `QUERY` (unknown method) | **500** | 403 |

`isActive` was fixed but its sibling date query params were not — same exception class. Per-field
patching will not converge. The durable fix is a single `@ControllerAdvice`:
`MethodArgumentTypeMismatchException`→400 (covers ALL query-param type errors at once),
`HttpMessageNotReadableException`→400 (null/malformed bodies),
`HttpRequestMethodNotSupportedException`→405 (unknown methods).

Root cause: Go's binding layer (go-playground validator + typed query binding) rejects each of these
with a descriptive 400; Java lets the parse/type/NPE exception reach a catch-all → 500. Fix on the
Java side: a `@ControllerAdvice` / `@ExceptionHandler` mapping bind/type/parse exceptions (and null
bodies, out-of-range numerics, unknown methods) to 400/405, matching Go. This one fix clears the
`not_a_server_error` failures on all 12 operations.

**Remaining schemathesis failures are fuzzer / gateway artifacts, not app-contract bugs:**
- `positive_data_acceptance` (RejectedPositiveData) — spec inline examples use boundary/user data
  invalid for the MAD tenant → service correctly 400s. **Excluded** in the test.
- `content_type_conformance` (UndefinedContentType) — Tomcat serves a `text/html` "HTTP Status 400"
  page when the fuzzer sends illegal header characters (rejected at the container layer, before the
  app). **Excluded** in the test.
- `MissingHeaderNotRejected` — spec marks `X-Tenant-ID` (and other platform headers) client-required,
  but **Kong injects them**, so omitting at the client yields 404, not a rejection. Meaningless when
  fronted by Kong; run schemathesis directly against the service if you want this check to be real.
- `UnsupportedMethodResponse` — 405/404 handling for undocumented HTTP methods.

Recommendation: fix the pagination overflow (→400); for the header/method negative checks either
exclude them for Kong-fronted runs or point schemathesis at the raw service.
