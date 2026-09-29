# Boundary Service — Legacy vs Canonical API Verification Report

**Date:** 2026-08-11
**Service:** boundary (Java/Spring Boot 4, Java 25), run locally from `target/boundary.jar` on port 8081
**Verified by:** live HTTP calls against a locally started instance (not unit tests)

---

## 1. Environment

| Component | State | Notes |
|---|---|---|
| PostgreSQL | localhost:5432, user `postgres` | DB `conformance` — all 3 boundary tables present with the current column set (`requestid`, standardized audit columns); migration history in `public.boundary_schema` |
| Redis | localhost:6379 (up) | Search caching worked normally |
| Kafka | **not running** locally | Event publishing degraded gracefully as designed: `KafkaPubSubClient` logs `Failed to publish to kafka topic ...` and the request still succeeds (matches Go nil-client degradation). 14 such ERROR log lines, all Kafka/OTLP export related — none affected API responses |
| OTLP collector | not running | Metric-export WARNs only, harmless |

## 2. Configuration changes made (application.yml ONLY — no code changed)

1. **`spring.datasource.url`** — DB name default `conformance_java` → `conformance` (the local DB that holds the boundary tables). Host/port/user/password already matched the provided local credentials (`localhost:5432`, `postgres`/`1234`).
2. **`digit.tenant-migration.filter.extra-skip-paths: [/boundary/v3/canonical]`** — **required discovery, please review (see §6).** Without it, every canonical request was rejected before reaching the controller with `[{"code":"MISSING_HEADER","message":"Missing mandatory header: X-Tenant-ID"}]`. That rejection comes from the **tenant-migration library's `TenantTransactionFilter`**, which mandates the `X-Tenant-ID` header on all non-excluded paths — independent of our controllers. The library exposes `filter.extra-skip-paths` for exactly this, so the canonical prefix was excluded via configuration.

## 3. Legacy API flow (`/boundary/v3/...`, X-* headers) — ALL PASS

Tenant `verify-tenant-4332`, headers `X-Tenant-Id`, `X-User-ID: legacy-user-9`, `X-Request-Id`.

| # | Call | Result |
|---|---|---|
| L1 | POST /v3/hierarchy (create `VER-ADMIN-4332`) | 201, `auditDetails.createdBy = legacy-user-9` (from X-User-ID) |
| L2 | GET /v3/hierarchy?hierarchyType=... | 200, returns created hierarchy |
| L3 | POST /v3/boundaries (`VERL-4332-A` with geometry, `VERL-4332-B`) | 201, ids assigned, audit populated |
| L4 | GET /v3/boundaries?codes=...&codes=... | 200, both rows |
| L5 | PUT /v3/boundaries/{id} (bare Boundary body) | 200, geometry/attributes updated, `createdBy` preserved, `modifiedBy` updated |
| L6 | PUT /v3/hierarchy/{id} | 200, hierarchyType preserved (not updatable) |
| L7 | POST /v3/relationship (root `VERL-4332-A`) | 201, materialized path `VERL-4332-A` |
| L8 | POST /v3/relationship (child `VERL-4332-B`, parent A) | 201, path `VERL-4332-A\|VERL-4332-B` |
| L9 | GET /v3/relationship?hierarchyType=...&includeChildren=true | 200, parent→children tree |
| L10 | PUT /v3/relationship/{id} | 200 |
| L11 | **Negative:** POST /v3/boundaries without `X-User-ID` | 400 `BAD_REQUEST` `"Missing X-Tenant-Id or X-User-ID header"` (unchanged legacy contract) |
| — | Malformed JSON body | 400 with Go-parity decode message (bare-array error shape) |

## 4. Canonical API flow (`/boundary/v3/canonical/...`, RequestMetadata in body, NO headers) — ALL PASS

Same tenant, `RequestMetadata` carrying `ts`, `msgId`, `requestId`, `correlationId`, `tenantId`, `userInfo.sub = canonical-user-7`.

| # | Call | Result |
|---|---|---|
| C1 | POST /canonical/hierarchy (create `VER-CANON-4332`) | 201, **`createdBy = canonical-user-7` = `userInfo["sub"]`** — same audit path as legacy |
| C2 | GET /canonical/hierarchy (`boundaryHierarchySearchCriteria` in body) | 200 |
| C3 | POST /canonical/boundaries (`VERC-4332-A/B`) | 201 |
| C4 | GET /canonical/boundaries (`boundarySearchCriteria.codes` in body) | 200, both rows |
| C5 | PUT /canonical/boundaries/{id} (`boundary` object in body) | 200, audit preserved/updated correctly |
| C6 | PUT /canonical/hierarchy/{id} | 200, hierarchyType preserved |
| C7 | POST /canonical/relationship (root) | 201, materialized path correct |
| C8 | POST /canonical/relationship (child) | 201 |
| C9 | GET /canonical/relationship (criteria in body, includeChildren) | 200, same tree shape |
| C10 | PUT /canonical/relationship/{id} | 200 |

Every canonical response carried `ResponseMetadata` with `requestId`/`correlationId`/`msgId`/`tenantId` echoed unchanged from the request and `ts` = response time.

**Canonical negative tests — ALL PASS (project-standard 400 array errors):**

| Test | Response |
|---|---|
| Missing `RequestMetadata` | `"Missing RequestMetadata in request body"` |
| Missing `userInfo.sub` on a write | `"Missing user id: RequestMetadata.userInfo.sub is required"` |
| `ts: 123` + no `tenantId` | `"RequestMetadata.tenantId must not be blank; RequestMetadata.ts must be greater than or equal to 1000000000000"` |
| Search without `codes` | `"Missing required parameter: codes"` |
| **X-* headers sent but no body metadata** | rejected with `"Missing RequestMetadata in request body"` — proves the canonical controller has **zero dependency on the X-* headers** |

## 5. Cross-API comparison — GET with the same values from both APIs

Each search executed twice — legacy (headers + query params) and canonical (RequestMetadata + criteria object in body) — then diffed after removing the canonical-only `ResponseMetadata` key (`jq -S` normalized).

| Comparison | Legacy result vs Canonical result |
|---|---|
| Search boundaries **created via legacy** (`VERL-4332-A/B`) through both APIs | **IDENTICAL** (byte-for-byte) |
| Search boundaries **created via canonical** (`VERC-4332-A/B`) through both APIs | **IDENTICAL** |
| Get hierarchy `VER-ADMIN-4332` through both APIs | **IDENTICAL** |
| Get relationships `VER-ADMIN-4332` (includeChildren) through both APIs | **IDENTICAL** |

This also confirms **cross-visibility**: data written through either API is read back identically through the other — both controllers converge on the same service/repository layer and the same tables.

## 6. Point needing your decision (flagged, not changed)

The tenant-migration library's `TenantTransactionFilter` normally does two things per request based on the `X-Tenant-ID` header: reject requests missing it, and set the per-tenant Postgres `search_path`/transaction. Excluding `/boundary/v3/canonical` from the filter (the only properties-level option) means **canonical requests do not get per-tenant schema switching**. In this local setup that is invisible (tenant-migration is disabled, all data lives in the `public` schema — verified for both APIs' writes). But in a deployment that uses schema-per-tenant, the canonical API would read/write `public` regardless of `RequestMetadata.tenantId`. Options if/when that matters (all need a code or library change, so not done now):
- extend the tenant-migration library to accept the tenant from a request attribute the canonical controller sets, or
- have the gateway (Kong) inject `X-Tenant-ID` for canonical routes from the JWT, keeping the filter active.

## 7. Verdict

- Legacy API: fully functional, behavior unchanged (headers, error contract, statuses all as before).
- Canonical API: fully functional at `{context-path}/v3/canonical/...`; `userInfo.sub` correctly becomes the user id in the same business flow; validation and error contract consistent with the project.
- Same inputs → same outputs across both APIs for every search compared.
- Only `application.yml` was modified (2 entries listed in §2).
