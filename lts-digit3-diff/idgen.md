# IDGen Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-idgen` (Spring Boot 3.4.5 / Java 17) · v2.9.3  
**New:** `idgen` (Go 1.24+ / Gin + GORM) · DIGIT 3.0

Both generate formatted, sequential, tenant-scoped IDs from named templates. v3.0 eliminates the MDMS dependency and dynamic PostgreSQL sequences, replacing them with self-owned format storage and an in-table sequence counter. 3.0 is a ground-up Go rewrite, not a port. This document covers only **idgen-specific** changes (platform-wide enhancements common to all 3.0 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | 2.9 (Java) | 3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.24, Gin |
| ORM / DB access | Spring Data JPA / Hibernate + custom QueryBuilder | GORM + raw SQL for sequence ops |
| Build | Maven | Go modules |
| Key libs | egov-tracer, mdms-client, Lombok, Spring Kafka | digit3 tracer/tenant-migration, go-redis, kafka-go, OpenTelemetry SDK |
| Format source | MDMS `IdFormat` master (fetched on every generate call) | `idgen_templates` table (self-owned; no external dependency) |
| Sequence model | `CREATE SEQUENCE` DDL per `(idName, tenantId)` at runtime | In-table counter via `SELECT FOR UPDATE` — no DDL privilege required |
| DB migration | Flyway (Spring Boot auto-config) | Flyway (external binary) + digit3/tenant-migration |

---

## 2. Features Added in 3.0

- **Self-owned format templates:** format stored in `idgen_templates`; MDMS dependency eliminated. Full CRUD via `GET/POST /idgen/v3/id/format` and `GET/PUT/DELETE /idgen/v3/id/format/:id`.
- **Automatic scoped resets:** per-generate boundary check (DAILY/MONTHLY/YEARLY) resets counter transparently when the boundary is crossed; v2.9 had no reset mechanism. Manual override via `POST /idgen/v3/id/sequence/reset`.
- **New format tokens:** `[district]`, `[random:N:charset]`, `[padding:N]`, `[static:value]` added.
- **Reset audit log:** `idgen_sequence_resets` table records every reset event (timestamp, user, previous value, reason).
- **In-process library:** `pkg/idgen` lets other Go services import the generation engine directly without an HTTP call.
- **Observability:** OTel spans per handler/service/repository layer; Prometheus counters `ids_generated_total` and `sequence_resets_total` with tenant/format dimensions.

> Carried over (parity): sequential ID generation from named templates, tenant-scoped sequences, most format tokens.

**Behavior changes to watch:** `[city]` and `[fy:]` (financial-year) tokens are **removed** in v3.0. Inline `format` override in the generate request body is no longer accepted — format must be pre-registered. `RequestInfo`/`ResponseInfo` envelope dropped entirely; tenant and user are now sourced from `X-Tenant-Id`/`X-User-Id` headers.

---

## 3. API Changes

| Concern | 2.9 endpoint(s) | 3.0 endpoint(s) |
|---|---|---|
| Generate IDs | `POST /egov-idgen/id/_generate` (inline `format` in body; `RequestInfo`/`ResponseInfo` envelope) | `POST /idgen/v3/id/_generate` (`X-Tenant-Id`/`X-User-Id` headers; format must be pre-registered; no envelope) |
| Format template CRUD | *(fetched from MDMS per call)* | `GET/POST /idgen/v3/id/format`, `GET/PUT/DELETE /idgen/v3/id/format/:id` |
| Sequence management | *(none)* | `GET /idgen/v3/id/sequence`, `POST /idgen/v3/id/sequence/reset` |
| DB migration | *(none)* | `POST /internal/migrate` |
| Health check | *(none)* | `GET /health` |

---

## 4. DB Changes

v2.9 used a single `id_generator` table plus one dynamically created PostgreSQL sequence per `(idName, tenantId)`. v3.0 replaces this with three normalized tables.

| 2.9 table | 3.0 table | Key differences |
|---|---|---|
| `id_generator` | `idgen_templates` | UUID PK; UNIQUE on `(tenant, name)`; stores format string; replaces MDMS as format source |
| *(per-name PostgreSQL sequences)* | `idgen_sequence_lookup` | UUID PK; FK to template; `currentValue` BIGINT; `resetPeriod` ENUM (NONE/DAILY/MONTHLY/YEARLY); `lastResetAt`; removes DDL privilege requirement |
| *(none)* | `idgen_sequence_resets` | Append-only reset audit log (timestamp, user, previous value, reason) |

Other DB notes: `id_generator` is functionally superseded but not explicitly dropped in migration scripts. All PKs are UUIDs; full audit columns added throughout.

---
