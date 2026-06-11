# Notification Service: 2.9 (Java) → 3.0 (Go)

## Overview
Two independent stateless Java/Spring Boot Kafka-consumer services (`egov-notification-mail` and `egov-notification-sms`) are merged into a single stateful Go/Gin service (`notification`) with a new companion utility service (`template-config`). The primary interface shifts from Kafka-only consumption to a REST HTTP API with async PubSub consumption retained as a secondary path.

## Tech stack

| | v2.9 (mail + sms) | v3.0 (notification + template-config) |
|---|---|---|
| Language | Java 17 | Go 1.22 |
| Framework | Spring Boot 3.4.5 | Gin 1.10.1 |
| ORM / DB access | None (stateless) | GORM 1.30 + pgx v5 |
| Build | Maven | Go modules |
| Key libs | spring-kafka, JavaMailSender, RestTemplate, egov-tracer, enc-client | gin, gorm, resty/v2, segmentio/kafka-go, go-redis/v8, digit3/tracer, opentelemetry, go-playground/validator |

## API changes

**Added**
- `POST /notification/v3/template` — Create a notification template (EMAIL or SMS)
- `PUT /notification/v3/template` — Create a new immutable version of an existing template
- `GET /notification/v3/template` — Search templates by id, type, or version
- `DELETE /notification/v3/template` — Delete a specific template version
- `POST /notification/v3/template/preview` — Dry-run render a template without sending
- `POST /notification/v3/email/send` — Send email via HTTP (replaces Kafka-only dispatch)
- `POST /notification/v3/sms/send` — Send SMS via HTTP (replaces Kafka-only dispatch)
- `GET /health` — Health check on both services
- `POST /internal/migrate` — Manual per-tenant Flyway schema migration trigger (ops/test)
- All `/template-config/v3/config` CRUD endpoints and `POST /template-config/v3/render` (new utility service, no v2.9 equivalent)

**Removed**
- `GET|POST /smsbounce/callback` — SMS delivery status callback endpoint; no equivalent in v3.0

**Changed**
- All routes now require `X-Tenant-ID` header. Send endpoints accept a `templateId` reference + `payload` instead of an inline rendered message body.

## Core logic & feature changes

- **Inline content → template references**: v2.9 callers embedded fully rendered message text in Kafka events. v3.0 callers pass a `templateId`; content is resolved, enriched, and rendered at send time using Go's `html/template` / `text/template` engine.
- **OTP expiry routing removed**: v2.9 SMS service routed expired OTP messages (`category=OTP`, `expiryTime < now`) to a dead-letter Kafka topic. No equivalent in v3.0.
- **SMS → email bridge removed**: the `send.sms.as.email.enabled` feature (looking up user emails via User service and forwarding SMS as email) has no v3.0 equivalent.
- **Configurable multi-provider SMS replaced**: v2.9 supported Generic and MSDG providers with configurable parameter maps, category routing, number blacklist/whitelist, and backup/error topic routing. v3.0 ships a single `SMSCountryProvider` (form-encoded POST); no blacklist/whitelist, no category routing, HTTP errors returned directly.
- **Payload enrichment via template-config**: new `enrich: true` flag on send/preview requests triggers the `template-config` service to extract JSONPath fields from the payload and call external APIs in parallel, returning a merged data map used as the template rendering context.
- **Filestore API version bump**: attachment download path changed from `filestore/v1/files/id` to `filestore/v3/files/<id>`.
- **Async consumption retained**: `EventConsumer` still subscribes to `notification-email` and `notification-sms` PubSub topics alongside the HTTP API.

## DB / schema changes

v2.9 had no database. v3.0 introduces two new Postgres tables, one per service:

- **`notification_template`** `(id UUID PK, templateid, tenantid, version INT, type, subject, content NOT NULL, ishtml, createdBy, createdTime, modifiedBy, modifiedTime, requestid)` — unique on `(tenantid, templateid, version)`
- **`template_config`** `(id UUID PK, templateid, tenantid, version INT, fieldmapping JSONB, apimapping JSONB, createdBy, createdTime, modifiedBy, modifiedTime, requestid)` — unique on `(tenantid, templateid, version)`

Both tables use per-tenant Postgres schema separation (`search_path` set per request from `X-Tenant-ID`). Flyway manages migrations via `db/migrate.sh`; `SCHEMA_SEPARATION_MODE=true` runs migrations per tenant schema.

## Notable architectural changes

- **Stateless → stateful**: v2.9 had no persistence layer. v3.0 adds Postgres with immutable versioned records for templates and enrichment configs.
- **Kafka-only → HTTP-primary + async-secondary**: notification dispatch is now triggered via REST; Kafka/Redis Streams consumption is a secondary path via a pluggable `digit3/tracer/pubsub` abstraction (switchable at runtime via `PUBSUB_TYPE`).
- **Two services → one + utility**: the two Spring Boot services merge into one Go service; enrichment concern is extracted into the standalone `template-config` service.
- **Single-tenant → multi-tenant schema isolation**: per-request `search_path` switching and Flyway-based per-tenant schema migrations replace the no-DB, no-isolation v2.9 model.
