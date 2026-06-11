# Localization Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-localization` (Spring Boot 3.4 / Java 17) · v2.9.3 — `/home/mithun/Desktop/lts-digit-core/.../egov-localization`
**New:** `localization` (Go 1.24+ / Gin + GORM) · DIGIT v3 — `digit3/src/services/localization`

Both store and serve localized messages keyed by **tenant + module + locale + code**, with CRUD plus a tenant/locale-aware resolution layer. v3 is a ground-up Go rewrite, not a port. This document covers only **localization-specific** changes (platform-wide enhancements common to all v3 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | v2 (Java) | v3 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.24+, idiomatic hexagonal (ports & adapters) |
| Web | Spring MVC (Tomcat), port 8087, ctx `/localization` | Gin, REST port 8080, ctx `/localization` |
| API surface | REST only | **REST + gRPC** (gRPC port 8089, all 7 operations) |
| DB access | JPA / Hibernate | GORM (pgx v5) |
| Cache | Redis only (Lettuce) | **Pluggable Redis *or* in-memory** (`CACHE_TYPE`) |
| Write persistence | Synchronous JPA to PostgreSQL (no Kafka) | Synchronous GORM + DB-native upserts; mutations also emit events |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go`, interface-based |

---

## 2. Features Added in v3

- **gRPC API** alongside REST — all operations (search, create, update, upsert, delete, bust-cache, find-missing) exposed over gRPC with optional reflection (`api/proto/localization/v1/localization.proto`).
- **Find-missing-messages** (net-new): `POST /v3/messages/missing` reports, per module, which `code`s are missing for which locales. Backed by an in-memory `tenant → module → code → [locales]` map loaded at startup and updated on writes. REST variant accepts an optional `locales[]` filter.
- **First-class upsert with DB-native semantics**: `INSERT ... ON CONFLICT (tenant_id, locale, module, code) DO UPDATE`, with in-batch dedup to avoid Postgres conflict errors. (v2 upsert was an app-level split into new-vs-existing.)
- **UUID as a stable external identifier**: update and delete now address rows by `uuid` (query-param UUIDs for delete) rather than by reconstructing the composite business key.
- **Batched bulk operations**: create/upsert run in batches of 100; full cache warm-up loads in batches of 1000.

> Carried over (parity): tenant + module + locale + code business key, Redis read-through caching with write invalidation, `cache-bust` endpoint, multi-tenancy.

**Behavior changes to watch:** v2 did **hierarchical tenant override** (`mh.panvel` → `mh` → `default`) plus a **fallback to `en_IN`/`default`** for missing codes. The v3 search path resolves directly on the exact tenant/module/locale (no documented hierarchy walk or English fallback in the resolution code) — confirm whether that fallback behavior is required and preserved.

---

## 3. API Changes

v2 exposed versioned, underscore-verb POST endpoints under `/messages`. v3 uses a **REST-verb model under `/v3`** plus a parallel gRPC surface.

| Concern | v2 endpoint(s) | v3 endpoint(s) |
|---|---|---|
| Search | `GET /messages`, `POST /messages/v1/_search` (query params), `POST /messages/v2/_search` (JSON body) | `GET /v3/messages` (params: `module`, `locale`, `codes`, `limit`, `offset`) |
| Create | `POST /messages/v1/_create` | `POST /v3/messages` (insert-only; conflict → 409) |
| Update | `POST /messages/v1/_update` (by module+locale+code) | `PUT /v3/messages` (by `uuid`; not-found → 404) |
| Upsert | `POST /messages/v1/_upsert` | `PUT /v3/messages/upsert` |
| Delete | `POST /messages/v1/_delete` (body of identities) | `DELETE /v3/messages` (query-param `uuid`/`uuids`) |
| Missing messages | *(none)* | `POST /v3/messages/missing` |
| Cache bust | `POST /messages/cache-bust` (whole cache) | `DELETE /v3/cache` (optional `module`/`locale` scoping) |

Pagination (`limit`/`offset`) is new on search. Tenant is now passed via the `X-Tenant-ID` header (was a `tenantId` field/param); user id via `X-User-ID` for audit.

> Doc caveat: the README and Postman collection are stale — they show `/messages/_upsert`, `/messages/_missing`, `/cache/_bust` and non-`/v3` paths. The route table in `internal/routes/routes.go` (`/v3/...`, `/upsert`, `/missing`, `/cache`) is authoritative.

---

