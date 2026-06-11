# IDGen Service: 2.9 (Java) → 3.0 (Go)

## Overview
`idgen` generates formatted, sequential, tenant-scoped IDs from named templates. The migration replaces a Java/Spring Boot service that depended on MDMS and dynamic PostgreSQL sequences with a self-contained Go/Gin service that owns its format data and tracks sequence state in-process.

## Tech stack
| | v2.9 | v3.0 |
|---|---|---|
| Language | Java 17 | Go 1.24 |
| Framework | Spring Boot 3.4.5 (Spring MVC) | Gin |
| ORM / DB access | Spring Data JPA / Hibernate + custom QueryBuilder | GORM + raw SQL for sequence ops |
| Build | Maven (spring-boot-maven-plugin) | Go modules (`go.mod`) |
| Key libs | egov-tracer, mdms-client, Lombok, Spring Kafka | digit3 tracer/tenant-migration, go-redis, kafka-go, OpenTelemetry SDK |
| DB migration | Flyway (Spring Boot auto-config) | Flyway (external binary) + digit3/tenant-migration |

## API changes

**Changed**
- `POST /egov-idgen/id/_generate` → `POST /idgen/v3/id/_generate`: `tenantId` and `userInfo` now sourced from `X-Tenant-Id` / `X-User-Id` headers instead of the `RequestInfo` body. Inline `format` override in the request body no longer accepted — format must be pre-registered. Response drops `ResponseInfo` envelope.

**Added**
- `GET/POST /idgen/v3/id/format` — list / create ID format templates
- `GET/PUT/DELETE /idgen/v3/id/format/:id` — read / update / delete a specific template
- `POST /idgen/v3/id/sequence/reset` — manually reset a sequence scoped to DAY / MONTH / YEAR / ALL
- `GET /idgen/v3/id/sequence` — list sequences for a tenant/format
- `POST /internal/migrate` — trigger per-tenant DB schema migration
- `GET /health` — health check

## Core logic & feature changes

- **Format source:** v2.9 fetches `IdFormat` master data from MDMS on every generate call. v3.0 stores all templates in `idgen_templates`; no MDMS dependency.
- **Sequence model:** v2.9 issues `CREATE SEQUENCE` DDL at runtime, one PostgreSQL sequence per `(idName, tenantId)`. v3.0 increments an in-table counter in `idgen_sequence_lookup` via `SELECT FOR UPDATE` / `nextval` — no DDL privilege required at runtime.
- **Automatic scoped resets:** v3.0 checks a period boundary (DAILY / MONTHLY / YEARLY) on each generate call and resets the counter transparently when the boundary is crossed. v2.9 had no reset mechanism.
- **Token changes:** `[city]` and `[fy:]` (financial-year) tokens removed. New tokens added: `[district]`, `[random:N:charset]`, `[padding:N]`, `[static:value]`.
- **Auth model:** `RequestInfo.userInfo.tenantId` body field → `X-Tenant-Id` + `X-User-Id` headers. `RequestInfo` / `ResponseInfo` envelope dropped entirely.
- **In-process library:** v3.0 ships `pkg/idgen` — other Go services can import the generation engine directly without an HTTP call.
- **Observability:** OTel spans added per handler/service/repository layer; Prometheus counters `ids_generated_total` and `sequence_resets_total` with tenant/format dimensions.

## DB / schema changes

v2.9 used a single `id_generator` table plus one dynamically created PostgreSQL sequence per `(idName, tenantId)`. v3.0 replaces this with three normalized tables:

- **`idgen_templates`** — UUID PK, tenant/name unique, format, audit columns. Replaces MDMS as format source.
- **`idgen_sequence_lookup`** — UUID PK, FK to template, `currentValue` BIGINT, `resetPeriod` ENUM (NONE/DAILY/MONTHLY/YEARLY), `lastResetAt`. Replaces per-name PostgreSQL sequences.
- **`idgen_sequence_resets`** — append-only audit log of reset events (timestamp, user, previous value, reason).

`id_generator` is functionally superseded (no explicit DROP in migration scripts). All PKs are UUIDs; full audit columns added throughout.

## Notable architectural changes

- **Format ownership shift:** Eliminating the MDMS round-trip removes a runtime dependency and allows self-service template management via the new CRUD API.
- **Sequence DDL eliminated:** Moving from dynamic `CREATE SEQUENCE` to an in-table counter removes the requirement for DDL privileges in the application database role.
- **Multi-tenancy model:** Optional per-tenant PostgreSQL schema isolation via `digit3/tenant-migration`; activated via `/internal/migrate` endpoint.
- **Runtime:** JVM/Spring Boot replaced by a Go binary — removes JVM startup overhead and egov-tracer/mdms-client platform dependencies.
