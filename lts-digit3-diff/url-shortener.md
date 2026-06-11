# URL Shortener Service: 2.9 (Java) → 3.0 (Go)

## Overview
`url-shortener` generates short URLs mapped to long URLs, resolves them via redirect, and supports per-tenant configuration of shortening behaviour. v3.0 is a full rewrite from Java/Spring Boot to Go/Gin, with enforced validity windows, CRUD management APIs, bidirectional event publishing, and per-tenant Postgres schema isolation.

## Tech stack
| | v2.9 | v3.0 |
|---|---|---|
| Language | Java 17 | Go 1.24 |
| Framework | Spring Boot 3.4 | Gin |
| ORM / DB access | JdbcTemplate (raw JDBC) | GORM + OTel instrumentation |
| Build | Maven | go modules |
| Key libs | Jedis, Hashids, egov-tracer, CustomKafkaTemplate | go-redis, crypto/rand, OpenTelemetry SDK, Kafka/Redis-Streams PubSub |
| DB migrations | Flyway (Spring Boot auto-config) | External shell script + tenant-aware migration library |

## API changes

**Added**
- `GET /url-shortener/v3/short-url/:key` — fetch short URL record as JSON (no redirect)
- `PUT /url-shortener/v3/short-url/:key` — update an existing short URL record
- `DELETE /url-shortener/v3/short-url/:key` — delete a short URL record
- `GET /url-shortener/v3/config` — fetch per-tenant shortener configuration
- `POST /url-shortener/v3/config` — create per-tenant shortener configuration
- `PUT /url-shortener/v3/config/:id` — update per-tenant shortener configuration
- `POST /url-shortener/v3/_migrate` — internal endpoint to trigger DB/data migration

**Changed**
- `POST /egov-url-shortening/shortener` → `POST /url-shortener/v3/short-url`: path changed; response is now a JSON object instead of a plain string; enforces validity window at create time.
- `GET /egov-url-shortening/{id}` → `GET /url-shortener/:key`: path changed; redirect code changed from 302 to 307 (method-preserving); expired keys are now rejected at resolve time.

## Core logic & feature changes
- **Validity enforcement:** v2.9 stored `validityInDays` but never checked it at resolve time. v3.0 validates the window on create and rejects expired keys on resolve.
- **Key generation:** Switched from deterministic Hashids (DB sequence) to cryptographically random base62 with configurable key length and collision-retry loop. Backward compatibility for existing hashid-format keys is not guaranteed.
- **Storage model:** v2.9 toggled between Redis-as-primary and Postgres at runtime. v3.0 always treats Postgres as canonical; Redis and an in-memory map serve as a read-through L1 cache only.
- **Multi-tenancy:** Static host-map (`MultiStateInstanceUtil`) replaced by `X-Tenant-ID` header with optional Postgres `search_path` switching per tenant.
- **Event publishing:** Single one-directional Kafka topic replaced by bidirectional PubSub (Kafka or Redis Streams) with 4 typed topics: created, updated, deleted, resolved.
- **Redirect semantics:** HTTP 302 changed to HTTP 307, preserving the HTTP method on redirect.

## DB / schema changes
**v2.9 — `eg_url_shortener`:** VARCHAR PK (the short key), `url` VARCHAR(1024), `expiry_time` BIGINT (stored, not enforced), no audit columns. Single shared schema.

**v3.0 — `url_shortener`:** UUID PK, separate `key` VARCHAR UNIQUE column, `url` TEXT (no length cap), `valid_from`/`valid_to` TIMESTAMP (enforced), full audit columns. New `url_config` table for per-tenant configuration.

Key structural breaks:
- VARCHAR PK replaced by UUID PK + separate `key` column — any FK or application reference to the old PK format will break.
- `url` column widened from VARCHAR(1024) to TEXT.
- Validity columns are now enforced constraints, not inert metadata.
- New table: `url_config` (key length, allowed domains, default validity, etc.).
- Migration tooling changed from Flyway to an external shell script + tenant-aware migration library.

## Notable architectural changes
- **Runtime:** JVM Spring Boot fat JAR replaced by a Go binary; eliminates JVM startup overhead and GC pauses.
- **Tracing:** `egov-tracer` replaced by native OpenTelemetry SDK wired through GORM and all service layers.
- **Multi-tenancy model:** Static host-map replaced by header-driven routing with optional per-tenant Postgres schema isolation via `search_path` middleware.
- **Event model:** One-directional Kafka producer replaced by bidirectional PubSub supporting both Kafka and Redis Streams with typed event topics.
