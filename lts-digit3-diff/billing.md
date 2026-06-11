# Billing & Payments: 2.9 (Java) → 3.0 (Go)

**Old:** `billing-service` + `collection-services` (Spring Boot / Java 17) · v2.9.3
**New:** `billing` (Go 1.23+ / Gin + GORM) · DIGIT v3

Two separate Spring Boot services (`billing-service` and `collection-services`) consolidated into a single Go/Gin service (`billing`). The merge eliminates inter-service HTTP calls for demand-to-payment linking and introduces REST-idiomatic verbs, per-tenant PostgreSQL schema separation, and full OpenTelemetry observability. v3 is a ground-up Go rewrite, not a port. This document covers only **billing-specific** changes (platform-wide enhancements common to all v3 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | v2 (Java) | v3 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot | Go 1.23, Gin v1.10 |
| Services | Two separate services (`billing-service` + `collection-services`) | Single binary; domain separation enforced by Go packages |
| ORM / DB access | JDBC | GORM v1.25 + pgx/v5 |
| Build | Maven (two repos) | Go modules |
| Key libs | spring-kafka, egov-tracer, Flyway (Spring) | shopspring/decimal, go-playground/validator v10, OpenTelemetry SDK, Flyway via `db/migrate.sh` |
| Write persistence | Async via Kafka → egov-persister | Synchronous GORM transactions; Kafka/Redis events for async bulk ops only |
| Event transport | Kafka-only | Kafka or Redis Streams selectable via `PUBSUB_TYPE` |

---

## 2. Features Added in v3

- **Demand lifecycle extensions:** `FROZEN` state with explicit `POST /v3/demands/:id/freeze` and `POST /v3/demands/:id/cancel` HTTP actions; optimistic locking via `version` column prevents lost updates.
- **GiST exclusion constraint:** enforces non-overlapping active/frozen/paid billing periods at the database level (was application-code only in v2.9).
- **Configurable arrear linking:** `DEMAND_ENABLE_ARREARS` flag; unpaid prior demands referenced via `arrear_demand_ids JSONB`.
- **HTTP-initiated bulk bill generation:** `POST /v3/bills/bulk-generate` replaces Kafka-only trigger; discovers consumers, batches (default 10, `BULK_BILL_CONSUMER_BATCH_SIZE`), publishes batch jobs, and returns counts synchronously.
- **Full CRUD on business services and tax heads:** `PUT/PATCH/DELETE /v3/business-services/:code` and `/v3/tax-heads/:code` (v2.9 was search-only).
- **Audit shadow tables:** every live table has a `*_audit` companion; every mutation writes an audit row with a `row_hash`.
- **Partial-failure batch responses:** HTTP 207 Multi-Status with `{ "success": [...], "failures": [...] }`.
- **Instrument age check:** rejects instruments older than `MAX_INSTRUMENT_DATE_AGE_DAYS` (default 90 days).

> Carried over (parity): demand create/update/search, bill generate/search/cancel, payment create/search/validate, business service and tax head management.

**Behavior changes to watch:** Tax period master (`/taxperiods/_search`) removed — period boundaries live on individual demands. Demand amendment workflow (`/amendment/*`) removed entirely. Receipt as a standalone resource removed — receipt number is now a field on `payment_details`. egov-workflow-v2 integration dropped. MDMS/localization/user-service clients removed — payment modes are typed Go enums.

---

## 3. API Changes

Context path changes: `/billing-service` and `/collection-services` → `/billing/v3/`. All endpoints use `X-Tenant-ID` and `X-User-ID` headers; the `RequestInfo` JSON wrapper is removed.

| Concern | v2 endpoint(s) | v3 endpoint(s) |
|---|---|---|
| Bill search | `POST /billing-service/v2/billV2/_search` | `GET /billing/v3/bills` |
| Bill generate | `POST /billing-service/v2/billV2/_generate` | `POST /billing/v3/bills/generate` |
| Bill cancel | `POST /billing-service/v2/billV2/_cancelbill` | `POST /billing/v3/bills/cancel` |
| Bulk bill generate | Kafka-only trigger | `POST /billing/v3/bills/bulk-generate` |
| Demand CRUD | `POST /_search`/`_create`/`_update` | `GET`/`POST`/`PUT /billing/v3/demands`; `GET /v3/demands/:id`, `PATCH /v3/demands/:id` |
| Demand lifecycle | *(none)* | `POST /billing/v3/demands/:id/freeze`, `POST /billing/v3/demands/:id/cancel` |
| Payment CRUD | `POST /_search`/`_create` | `GET`/`POST /billing/v3/payments`; `GET /v3/payments/:id` |
| Payment validate | `POST /v2/receipts/_validate` | `POST /billing/v3/payments/validate` |
| Business service CRUD | Search only | `GET/PUT/PATCH/DELETE /billing/v3/business-services/:code` |
| Tax head CRUD | Search only | `GET/PUT/PATCH/DELETE /billing/v3/tax-heads/:code` |
| Tax periods | `/taxperiods/_search` | *(removed — boundaries on demands)* |
| Amendments | `/amendment/_create\|_update\|_search` | *(removed)* |
| Receipts (standalone) | `/v2/receipts/*` | *(removed — receipt number on `payment_details`)* |
| Remittances | `/remittances/*`, `/bankAccountServiceMapping/*` | *(removed)* |

---

## 4. DB Changes

| v2 table | v3 table | Key differences |
|---|---|---|
| Demand tables | (same names, restructured) | UUID PKs replace sequences (`seq_egbs_demand`, etc.); `arrear_demand_ids` JSONB; `version` column for optimistic locking; GiST exclusion constraint on billing periods |
| Bill tables | (same names, restructured) | UUID PKs replace sequences (`seq_egbs_bill`); `requestid TEXT` added |
| Payment tables | (same names, restructured) | UUID PKs replace sequences; `requestid TEXT` added |
| *(none)* | `*_audit` shadow tables | Created for every live table; append-only; stores mutation row with `row_hash` |

Other DB notes: human-readable numbers (bill number, receipt number, transaction number) still sourced from IDGen. Per-tenant PostgreSQL schema isolation available via `SCHEMA_SEPARATION_MODE=true`; schema bootstrap is event-driven (`account-migration` topic) or manual (`/internal/migrate`).

---
