# Boundary Service: 2.9 (Java) → 3.0 (Go)

## Overview
`boundary` manages geographical boundary entities, hierarchy type definitions, and parent-child boundary relationships with multi-tenant isolation. v3.0 rewrites the service from Java/Spring Boot to Go/Gin, replaces the Kafka-persister pattern with direct DB writes, and replaces the eGov `RequestInfo` auth model with HTTP headers.

## Tech stack

| | v2.9 | v3.0 |
|---|---|---|
| Language | Java 17 | Go 1.23 (toolchain 1.24) |
| Framework | Spring Boot 3.2.2 | Gin v1.10.1 |
| ORM / DB access | Spring JDBC (`JdbcTemplate` + manual row mappers) | GORM v1.25 (`gorm.io/driver/postgres`) |
| Build | Maven (`spring-boot-maven-plugin`) | Go modules (`go.mod`) |
| Key libs | egov-tracer 2.9.2, digit-models, mdms-client, Lombok | gin, gorm, go-redis/v8, sarama, opentelemetry SDK, digitnxt/digit3 tracer/tenant-migration |
| DB migration | Flyway classpath (disabled by default; persister-driven) | Flyway filesystem via shell script + Docker; digit3/tenant-migration for per-tenant schema |

## API changes

Context path changes from `/boundary-service` to `/boundary`; routes versioned under `/v3`.

**Added**
- `PUT /boundary/v3/hierarchy/:id` — Update hierarchy type list; `hierarchyType` is immutable
- `PUT /boundary/v3/relationship/:id` — Update relationship; `code` field is immutable (enforced at handler)
- `GET /health` — Health check
- `POST /internal/migrate` — Trigger per-tenant Flyway migration

**Changed**
- All `POST /_search` endpoints replaced by `GET` with query parameters:
  - `GET /boundary/v3/boundaries` — `codes` query param required (was POST + `@ModelAttribute`)
  - `GET /boundary/v3/hierarchy` — `hierarchyType` query param (was POST search)
  - `GET /boundary/v3/relationship` — query params including `includeChildren`, `includeParents`
- `POST /_create` and `POST /_update` for all three resources consolidated to `POST` (create, returns 201) and `PUT /:id` (update by path ID)
- Auth moved from `RequestInfo` JSON body to `X-Tenant-Id`, `X-User-ID`, `X-Request-Id` headers across all endpoints
- `ResponseInfo` envelope removed; all responses return domain data directly
- `POST /boundary/v3/boundaries` (create): `code` is now required; geometry structure is validated before persist

## Core logic & feature changes

- **Geometry validation:** v3.0 validates GeoJSON type and coordinate structure at handler and service layers before persisting. v2.9 stored raw JSONB with no validation.
- **Caching added:** Read-through cache (Redis or in-memory) keyed by `tenantId:<resource>:search:<criteria>`. Invalidated on every create/update. v2.9 had no application-level caching.
- **Hierarchy update enforcement:** `hierarchyType` locked on update; only boundary type list may change.
- **Relationship `code` immutability:** Enforced at handler level on `PUT`; rejected with error before reaching service.
- **Materialized path cascade on relationship update:** v2.9 recomputed `ancestralMaterializedPath` for all descendants in a batch transaction on parent update. Cascade behavior is not present in v3.0 implementation (may be deferred).
- **Persistence timing:** v2.9 published to Kafka and relied on an external egov persister service for all SQL. v3.0 writes directly to DB via GORM, then publishes events post-write (fire-and-forget, graceful degradation if broker unavailable).
- **PubSub backend:** Toggled via `PUBSUB_TYPE` env var — Kafka (sarama) or Redis Streams. v2.9 was Kafka-only.
- **Per-tenant schema separation:** Optional (`SCHEMA_SEPARATION_MODE=true`); Flyway runs per schema on tenant onboarding. Off by default.

## DB / schema changes

All three tables are renamed with `_v1` suffix (`boundary_v1`, `boundary_hierarchy_v1`, `boundary_relationship_v1`). Original tables are not dropped.

| Breaking change | Detail |
|---|---|
| `id` column type | `VARCHAR(64)` → `UUID` (migration `V20260413170000`) |
| `additionalDetails` field | Renamed to `additionalattributes` on `boundary_v1` |
| Audit column names | `lastmodifiedby`/`lastmodifiedtime` → `"modifiedBy"`/`"modifiedTime"`; `createdby`/`createdtime` → `"createdBy"`/`"createdTime"` |
| `requestid TEXT` | Added to all three tables |

## Notable architectural changes

- **Kafka persister → direct GORM writes.** v2.9 owned no SQL; an external persister service consumed Kafka events and executed all DML. v3.0 owns its DB writes; Kafka/Redis events are post-write notifications.
- **Auth model: `RequestInfo` body → HTTP headers.** Removes eGov platform coupling; compatible with standard API gateways.
- **eGov platform decoupled.** `egov-tracer`, `digit-models`, `mdms-client`, persister, workflow, localization, and idgen dependencies removed. Only digit3 tracer and tenant-migration libraries remain.
- **REST verb semantics enforced.** All-POST (`/_create`, `/_search`, `/_update`) replaced by `POST` / `GET` / `PUT /:id` with proper status codes (201 on create).
