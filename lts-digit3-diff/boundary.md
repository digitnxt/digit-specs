# Boundary Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-boundary-service` (Spring Boot 3.2.2 / Java 17) · v2.9.3  
**New:** `boundary` (Go 1.23+ / Gin + GORM) · DIGIT 3.0

Both manage geographical boundary entities, hierarchy type definitions, and parent-child boundary relationships with multi-tenant isolation. v3.0 is a ground-up Go rewrite replacing the Kafka-persister pattern with direct DB writes and the eGov `RequestInfo` auth model with HTTP headers. This document covers only **boundary-specific** changes (platform-wide enhancements common to all 3.0 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | 2.9 (Java) | 3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.2.2 | Go 1.23 (toolchain 1.24), Gin v1.10.1 |
| ORM / DB access | Spring JDBC (`JdbcTemplate` + manual row mappers) | GORM v1.25 |
| Build | Maven | Go modules |
| Key libs | egov-tracer 2.9.2, digit-models, mdms-client, Lombok | gin, gorm, go-redis/v8, sarama, opentelemetry SDK, digit3 tracer/tenant-migration |
| Write persistence | Kafka → external egov persister service (all SQL) | Direct GORM writes; post-write event publish (fire-and-forget) |
| DB migration | Flyway classpath (disabled by default) | Flyway filesystem via shell script + digit3/tenant-migration |
| Caching | None | Read-through cache (Redis or in-memory) keyed by tenant/resource/criteria; invalidated on create/update |
| Event backend | Kafka-only | Kafka (sarama) or Redis Streams via `PUBSUB_TYPE` |

---

## 2. Features Added in 3.0

- **Geometry validation:** GeoJSON type and coordinate structure validated at handler and service layers before persisting. v2.9 stored raw JSONB with no validation.
- **Update endpoints:** `PUT /boundary/v3/hierarchy/:id` (boundary type list updateable; `hierarchyType` field immutable) and `PUT /boundary/v3/relationship/:id` (`code` field immutable, enforced at handler).
- **Application-level caching:** read-through cache (Redis or in-memory) keyed by `tenantId:<resource>:search:<criteria>`; invalidated on every create/update.
- **PubSub backend flexibility:** Kafka (sarama) or Redis Streams toggled via `PUBSUB_TYPE` env var.

> Carried over (parity): boundary CRUD, hierarchy type definitions, parent-child relationship management, materialized path on boundaries, multi-tenancy.

**Behavior changes to watch:** Materialized path cascade on relationship update — v2.9 recomputed `ancestralMaterializedPath` for all descendants in a batch transaction on parent update; this cascade behavior is **not present** in v3.0 and may be deferred.

---

## 3. API Changes

Context path changes from `/boundary-service` to `/boundary`; routes versioned under `/v3`. Auth moved from `RequestInfo` JSON body to `X-Tenant-Id`, `X-User-ID`, `X-Request-Id` headers; `ResponseInfo` envelope removed.

| Concern | 2.9 endpoint(s) | 3.0 endpoint(s) |
|---|---|---|
| Boundary search | `POST /boundary-service/boundary/_search` | `GET /boundary/v3/boundaries` (`codes` query param required) |
| Boundary create | `POST /boundary-service/boundary/_create` | `POST /boundary/v3/boundaries` (returns 201; `code` required; geometry validated) |
| Boundary update | `POST /boundary-service/boundary/_update` | `PUT /boundary/v3/boundaries/:id` |
| Hierarchy search | `POST /boundary-service/hierarchytype/_search` | `GET /boundary/v3/hierarchy` |
| Hierarchy create | `POST /boundary-service/hierarchytype/_create` | `POST /boundary/v3/hierarchy` |
| Hierarchy update | `POST /boundary-service/hierarchytype/_update` | `PUT /boundary/v3/hierarchy/:id` (`hierarchyType` immutable) |
| Relationship search | `POST /boundary-service/boundary-relations/_search` | `GET /boundary/v3/relationship` (`includeChildren`, `includeParents` params) |
| Relationship create | `POST /boundary-service/boundary-relations/_create` | `POST /boundary/v3/relationship` |
| Relationship update | `POST /boundary-service/boundary-relations/_update` | `PUT /boundary/v3/relationship/:id` (`code` immutable) |
| DB migration | *(none)* | `POST /internal/migrate` |
| Health check | *(none)* | `GET /health` |

---

## 4. DB Changes

All three tables renamed with `_v1` suffix; original tables not dropped.

| 2.9 table | 3.0 table | Key differences |
|---|---|---|
| `boundary` | `boundary_v1` | `id` type changed `VARCHAR(64)` → `UUID` (migration `V20260413170000`); `requestid TEXT` added |
| `boundary_hierarchy` | `boundary_hierarchy_v1` | `id` type changed `VARCHAR(64)` → `UUID`; `requestid TEXT` added |
| `boundary_relationship` | `boundary_relationship_v1` | `id` type changed `VARCHAR(64)` → `UUID`; `requestid TEXT` added |

Other DB notes: original tables coexist in the same schema and are not dropped. The `id` → UUID type change is a breaking change for any consumer holding references to old VARCHAR boundary IDs.

---
