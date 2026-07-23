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
