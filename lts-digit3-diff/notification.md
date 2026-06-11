# Notification Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-notification-mail` + `egov-notification-sms` (Spring Boot 3.4.5 / Java 17) · v2.9.3  
**New:** `notification` + `template-config` (Go 1.22+ / Gin + GORM) · DIGIT v3

Two independent stateless Kafka-consumer services (`egov-notification-mail` and `egov-notification-sms`) are merged into a single stateful Go service (`notification`) with a companion enrichment utility (`template-config`). The primary dispatch interface shifts from Kafka-only consumption to a REST HTTP API. v3 is a ground-up Go rewrite, not a port. This document covers only **notification-specific** changes (platform-wide enhancements common to all v3 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | v2 (Java) | v3 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.22, Gin 1.10.1 |
| Services | Two separate stateless services (mail + sms) | One stateful service (`notification`) + utility service (`template-config`) |
| ORM / DB access | None (stateless — no persistence layer) | GORM 1.30 + pgx v5 |
| Build | Maven (two repos) | Go modules |
| Key libs | spring-kafka, JavaMailSender, RestTemplate, egov-tracer, enc-client | gin, gorm, resty/v2, segmentio/kafka-go, go-redis/v8, digit3/tracer, opentelemetry, go-playground/validator |
| Dispatch model | Kafka-only consumer (async only) | HTTP REST primary + Kafka/Redis Streams async secondary (pluggable via `PUBSUB_TYPE`) |
| SMS provider | Generic + MSDG with configurable routing, blacklist/whitelist, category routing | Single `SMSCountryProvider` (form-encoded POST); no routing layer |

---

## 2. Features Added in v3

- **Versioned template management:** templates stored in Postgres as immutable versioned records (EMAIL or SMS); full CRUD via `POST/GET/PUT/DELETE /notification/v3/template`.
- **HTTP send endpoints:** `POST /notification/v3/email/send` and `POST /notification/v3/sms/send` replace Kafka-only dispatch; callers pass `templateId` + `payload` instead of inline rendered content.
- **Template preview (dry-run):** `POST /notification/v3/template/preview` renders content using Go `html/template`/`text/template` without dispatching.
- **Payload enrichment (`template-config`):** `enrich: true` flag on send/preview requests triggers the companion service to extract JSONPath fields and call external APIs in parallel, merging results as the rendering context.
- **`template-config` utility service:** standalone service for enrichment config CRUD (`/template-config/v3/config`) and on-demand rendering (`POST /template-config/v3/render`).
- **Stateful Postgres persistence:** per-tenant schema separation replaces the completely stateless v2.9 model.

> Carried over (parity): async `EventConsumer` subscribes to `notification-email` and `notification-sms` PubSub topics; email and SMS delivery capability; multi-tenancy via `X-Tenant-ID`.

**Behavior changes to watch:** OTP expiry routing (routing expired OTP messages to a dead-letter Kafka topic) is removed with no equivalent. The SMS→email bridge (`send.sms.as.email.enabled`) is removed. Multi-provider SMS routing (Generic/MSDG providers), number blacklist/whitelist, and category routing are all removed — v3.0 ships a single `SMSCountryProvider` with no routing layer.

---

## 3. API Changes

v2.9 had no REST API (dispatch was Kafka-only). v3.0 introduces HTTP-primary endpoints alongside retained async Kafka/Redis consumption.

| Concern | v2 endpoint(s) | v3 endpoint(s) |
|---|---|---|
| Send email | Kafka topic `notification-email` | `POST /notification/v3/email/send` (+ Kafka retained as secondary) |
| Send SMS | Kafka topic `notification-sms` | `POST /notification/v3/sms/send` (+ Kafka retained as secondary) |
| Template CRUD | *(none)* | `POST/GET/PUT/DELETE /notification/v3/template` |
| Template preview | *(none)* | `POST /notification/v3/template/preview` |
| Enrichment config | *(none)* | `/template-config/v3/config` CRUD; `POST /template-config/v3/render` |
| SMS bounce callback | `GET|POST /smsbounce/callback` | *(removed)* |
| DB migration | *(none)* | `POST /internal/migrate` |

All routes require `X-Tenant-ID` header. Send endpoints accept `templateId` + `payload`; inline rendered message content is no longer accepted.

---

## 4. DB Changes

v2.9 had no database. v3.0 introduces two new Postgres tables, one per service.

| v2 table | v3 table | Key differences |
|---|---|---|
| *(none — stateless)* | `notification_template` | UUID PK; UNIQUE on `(tenantid, templateid, version)`; stores versioned immutable EMAIL/SMS templates |
| *(none — stateless)* | `template_config` | UUID PK; UNIQUE on `(tenantid, templateid, version)`; `fieldmapping` and `apimapping` JSONB for payload enrichment |

Other DB notes: both tables use per-tenant Postgres schema separation (`search_path` set per request from `X-Tenant-ID`). `SCHEMA_SEPARATION_MODE=true` runs Flyway migrations per tenant schema. `requestid` stored on both tables for request-trace correlation.

---
