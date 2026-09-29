# Access Control conformance — Java (`/access-java/v3`) vs Go (`/access/v3`)

Live runs through Kong, tenant **MAD1**, token = MAD1 realm **SUPERUSER**.

The service issues the RBAC/JBAC rules **Kong itself pulls** to authorize traffic, so the suite is
deliberately non-destructive: creates only on `/api/conformance/…` paths with random non-SUPERUSER
roles; deletes only ids it created; fuzzer-created rules are captured and cleaned up after each run;
`/internal/*` and all `DELETE …/rules/{tenant,id}` are skipped. The governing `/access` /
`/access-java` rules were never touched (verified before/after).

## Behavioral + contract suite (95 tests)

| Target | Result |
|---|---|
| **Go** | **83 passed / 12 skipped / 0 failed ✅** |
| **Java** (after routing fix) | **83 passed / 12 skipped / 0 failed ✅** |

The 12 skips are intentional safeguards (6 `/internal/*`, 3 tenant-delete, 3 internal-consistency).
Java reached parity after the trailing-slash routing fix (see below / changes.md).

### Java routing bug — FIXED
Java (Spring Boot 3) mapped collection/by-id endpoints with a trailing slash, so the documented
no-slash spec paths returned `NoResourceFoundException`. Option A (annotations changed to the
documented no-slash paths) was deployed → recovered all 31 behavioral failures. Detail in changes.md.

---

## Schemathesis property-based suite (phases: examples + fuzzing; `full` = 100 examples/endpoint)

| Target | `full` (100) — first pass | `full` — after Java fixes |
|---|---|---|
| Go   | 2 failed / 8 passed / 8 skipped (NUL→500) | — (unchanged; NUL deferred) |
| Java | 2 failed / 8 passed / 8 skipped (envelope) | **1 failed** / 9 passed / 8 skipped (only NUL) |

> The single remaining Java failure is the accepted NUL-in-string class (see Finding 1); the
> envelope failures are gone. `bounded` (25) under-covers — it missed real bugs — so `full` (100)
> is the trustworthy pass. The 8 skips per run are `/internal/*` (4) + all DELETEs (4).

### Confirmed findings (direct-probe verified, Go vs Java)

| Malformed input | Go | Java (after fixes) |
|---|---|---|
| Empty-named query param `?=false` | **200** (ignored) | **200** ✅ (was 400 + Spring envelope) |
| Literal `null` request body | 400 | **400** ✅ (was 500), proper `[Error]` array |
| Valid non-ASCII UTF-8 (`café 日本語 …`) | **201** ✅ | **201** ✅ |
| NUL byte in list filter (`name`/`httpMethod`=…`\x00`) | **500** (deferred) | **500** (deferred)† |
| NUL byte in **body** field (`description`) | **500** (deferred) | **500** (deferred) |

† After the envelope/routing fix, Java's query-filter NUL now reaches the DB and 500s like Go
(previously it got an accidental routing-400). Same accepted class.

### Finding 1 — NUL byte in a string → 500  (both Go and Java) — ACCEPTED / DEFERRED
**Shared, non-human-input class.** A string containing a NUL byte (`\x00`) that reaches Postgres
produces an unhandled **500 `AccessControl.InternalError`** instead of a clean **400**. Confirmed on
**both** services on the create/bulk **body** path and the list **query-filter** path (RBAC
`httpMethod`/`path`, JBAC `enforcement`/`name`). Spans RBAC and JBAC. Same class as employee
(`designations`) and individual (`givenName`).

**Decision: accepted / won't-fix for now** (agreed with maintainer). Rationale:
- Verified **valid non-ASCII UTF-8** (`café 日本語 münchen Ñoño Москва`) is handled cleanly → **201**
  on both services, so **no real user is affected** — only NUL / invalid-UTF-8 machine junk triggers it.
- Low severity: the only downside is observability noise (a client error surfacing as a 500), not a
  functional break or security hole (Postgres safely rejects the NUL).
- Consistent with how the same class was treated on employee/individual.

`not_a_server_error` will still flag this in fuzzer runs — that's expected; treat a 500 whose input
contains `%00` as this accepted finding, not a regression. **Optional platform-wide fix** (fix-once,
fixes-all): a shared input filter rejecting NUL / invalid UTF-8 → 400 before the DB layer, on both
body and query paths; do **not** block valid non-ASCII UTF-8.
(Non-NUL exotic input — huge floats, unknown extra keys, invalid enum values — is handled correctly
by both, 201/200/empty, so this is specifically NUL-in-string.)

### Finding 2 — Java error envelope + empty-named query param — FIXED ✅
Previously `GET /rbac/rules?=false` (empty-named query param) returned **400** with Spring Boot's
default error object `{"timestamp","status","error","path"}` (not the contract's `[Error]` array),
failing `response_schema_conformance`; Go returned 200. Also, a literal `null` request body 500'd.

**Fixed and re-verified (Java full re-run + direct probe):**
- Empty-named query param `?=false` → **200** (matches Go; no more Spring envelope).
- Literal `null` request body → **400** with the proper `[Error]` array (`AccessControl.ValidationFailed`).
- Java `full` re-run: **1 failed / 9 passed / 8 skipped** — zero schema/envelope violations remain;
  the single failure is a `GET /jbac/rules` 500 whose input carries `name=…%00…` — i.e. the
  **accepted Finding-1 NUL class**, not the envelope bug.

---

## Checks kept vs excluded (schemathesis)
Active: `response_schema_conformance`, `status_code_conformance`, `not_a_server_error`.
Excluded (gateway/env artifacts, not contract violations): `ignored_auth`,
`response_headers_conformance`, `negative_data_rejection`, `positive_data_acceptance`,
`missing_required_header`, `unsupported_method`, `content_type_conformance`. Rationale in changes.md.

## Summary (current)
- **Behavioral:** Go 83/83, Java 83/83 (after the routing fix). ✅
- **Robustness (fuzzer):**
  - **Java routing** (trailing slash) — **FIXED** ✅
  - **Java error-envelope + null body** — **FIXED** ✅ (re-run: no schema/envelope violations remain)
  - **NUL-in-string → 500** (both Go and Java) — **ACCEPTED / DEFERRED** as non-human input
    (valid Unicode verified working). This is the only class still flagged by `not_a_server_error`.
- **Net:** both implementations are behaviorally conformant and match on every real-data case; the
  sole outstanding item is the intentionally-deferred NUL/invalid-UTF-8 hardening, optionally fixed
  platform-wide.
