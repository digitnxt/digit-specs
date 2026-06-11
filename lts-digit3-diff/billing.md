# Billing & Payments: 2.9 (Java) → 3.0 (Go)

## Overview
Two separate Spring Boot services (`billing-service` and `collection-services`) have been consolidated into a single Go/Gin service (`billing`). The merge eliminates inter-service HTTP calls for demand-to-payment linking and introduces REST-idiomatic verbs, per-tenant PostgreSQL schema separation, and full OpenTelemetry observability.

## Tech stack

| | v2.9 | v3.0 |
|---|---|---|
| Language | Java 17 | Go 1.23 |
| Framework | Spring Boot | Gin v1.10 |
| ORM / DB access | JDBC | GORM v1.25 + pgx/v5 |
| Build | Maven (two repos) | Go modules (single binary) |
| Key libs | spring-kafka, egov-tracer, Flyway (Spring) | shopspring/decimal, go-playground/validator v10, OpenTelemetry SDK, Flyway via `db/migrate.sh` |

## API changes

Context path changes: `/billing-service` and `/collection-services` → `/billing/v3/`.
All v3.0 endpoints use `X-Tenant-ID` and `X-User-ID` headers; the `RequestInfo` JSON wrapper is gone.

**Added**
- `GET /health` — liveness check returning service name and version
- `POST /internal/migrate` — manual tenant schema bootstrap without messaging
- `GET /PUT /PATCH /DELETE /v3/business-services/:code` — full CRUD on business service records (v2.9 was search-only)
- `GET /PUT /PATCH /DELETE /v3/tax-heads/:code` — full CRUD on tax heads (v2.9 was search-only)
- `GET /v3/demands/:id`, `PATCH /v3/demands/:id` — single-demand fetch and partial update
- `POST /v3/demands/:id/freeze` and `POST /v3/demands/:id/cancel` — first-class lifecycle transitions
- `GET /v3/payments/:id` — single-payment fetch
- `POST /v3/bills/bulk-generate` — HTTP entry point for bulk bill generation (was Kafka-only in v2.9)

**Removed**
- `/taxperiods/_search` — tax period master dropped; period boundaries live on individual demands
- `/amendment/_create|_update|_search` — demand amendment workflow removed entirely
- `/v2/receipts/*` — receipt as a standalone resource removed; receipt number is now a field on `payment_details`
- `/remittances/*` and `/bankAccountServiceMapping/*` — both removed with no replacement
- `/{moduleName}/_workflow` and receipt `_workflow` — egov-workflow-v2 integration dropped
- `_plainsearch`, `_migratetov1`, and `_fetchbill` endpoints from both v2.9 services

**Changed**
- Bill search/generate/cancel: POST `_search`/`_generate`/`_cancelbill` → `GET /v3/bills`, `POST /v3/bills/generate`, `POST /v3/bills/cancel`
- Demand search/create/update: POST `_search`/`_create`/`_update` → `GET`/`POST`/`PUT /v3/demands`
- Payment search/create: POST `_search`/`_create` → `GET`/`POST /v3/payments`
- All request bodies are now bare JSON (no `RequestInfo` wrapper); all query parameters use `GET` rather than POST bodies

## Core logic & feature changes

- **Demand lifecycle** now includes `FROZEN` state; `freeze` and `cancel` are explicit HTTP actions. Optimistic locking via `version` column prevents lost updates.
- **GiST exclusion constraint** on `demands` enforces non-overlapping active/frozen/paid billing periods at the database level — previously enforced in application code only.
- **Arrear linking** is configurable via `DEMAND_ENABLE_ARREARS`; unpaid prior demands are referenced via `arrear_demand_ids JSONB`.
- **Bulk bill generation** is now HTTP-initiated: the endpoint discovers consumers, batches them (default 10, `BULK_BILL_CONSUMER_BATCH_SIZE`), publishes batch jobs, and returns counts synchronously. Background consumer processes jobs.
- **Payment validation** (`POST /v3/payments/validate`) runs full apportionment without persisting — unchanged in purpose but now a top-level path.
- **Instrument age check** rejects instruments older than `MAX_INSTRUMENT_DATE_AGE_DAYS` (default 90 days).
- **Persistence is now synchronous** — v2.9 wrote demands/bills via Kafka consumers; v3.0 writes in the request handler. Kafka/Redis events are used only for async bulk operations; publish failure is non-fatal.
- **Partial-failure batch responses** return HTTP 207 Multi-Status with `{ "success": [...], "failures": [...] }`.
- **MDMS/localization/user-service clients removed** — payment modes are typed Go enums; no SMS localization or on-demand user creation.

## DB / schema changes

- **Audit column rename** (migration V20260427120000): `created_by`, `created_time`, `last_modified_by`, `last_modified_time` → `createdBy`, `createdTime`, `modifiedBy`, `modifiedTime`. Any consumer reading audit columns by name must update column references.
- **`requestid TEXT` column** added to every table (migration V20260415195000) for request-trace correlation.
- **UUID primary keys** replace database sequences (`seq_egbs_bill`, `seq_egbs_demand`, etc.). Human-readable numbers (bill number, receipt number, transaction number) still come from IDGen.
- **`*_audit` shadow tables** are now created for every live table; every mutation writes an audit row with a `row_hash`.

## Notable architectural changes

- **Single binary replaces two services.** No inter-service HTTP between demand and payment domains; domain separation is enforced by Go packages within the same process.
- **PostgreSQL schema-per-tenant** (opt-in via `SCHEMA_SEPARATION_MODE=true`): `search_path` is set per request by GORM middleware keyed on `X-Tenant-ID`. Schema bootstrap is event-driven (Kafka/Redis `account-migration` topic) or manual (`/internal/migrate`).
- **Dual PubSub transport**: Kafka or Redis Streams selectable at deploy time via `PUBSUB_TYPE`; no code changes required to switch.
- **Full OpenTelemetry**: OTLP/HTTP traces, Prometheus metrics on a dedicated port (default 9090), and structured OTel logs replace MDC/egov-tracer. GORM queries appear as child spans automatically.
