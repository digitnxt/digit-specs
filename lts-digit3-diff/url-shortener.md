# URL Shortener Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-url-shortening` (Spring Boot 3.4 / Java 17) · v2.9.3  
**New:** `url-shortener` (Go 1.24+ / Gin + GORM) · DIGIT v3

Both generate short URLs mapped to long URLs and resolve them via redirect. v3.0 is a ground-up Go rewrite, not a port, adding validity enforcement, full CRUD management APIs, bidirectional event publishing, and per-tenant Postgres schema isolation. This document covers only **url-shortener-specific** changes (platform-wide enhancements common to all v3 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | v2 (Java) | v3 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4 | Go 1.24, Gin |
| ORM / DB access | JdbcTemplate (raw JDBC) | GORM + OTel instrumentation |
| Build | Maven | Go modules |
| Key libs | Jedis, Hashids, egov-tracer, CustomKafkaTemplate | go-redis, crypto/rand, OpenTelemetry SDK, Kafka/Redis-Streams PubSub |
| Storage model | Redis-as-primary **or** Postgres (runtime toggle) | Postgres canonical; Redis + in-memory map as read-through L1 cache only |
| DB migrations | Flyway (Spring Boot auto-config) | External shell script + tenant-aware migration library |
| Event model | One-directional Kafka producer | Bidirectional PubSub (Kafka or Redis Streams) with 4 typed topics |
| Multi-tenancy | Static host-map (`MultiStateInstanceUtil`) | `X-Tenant-ID` header with optional per-tenant Postgres `search_path` switching |

---

## 2. Features Added in v3

- **Validity enforcement:** expiry window validated on create; expired keys rejected at resolve time. v2.9 stored `validityInDays` but never checked it.
- **Cryptographically random key generation:** base62 with configurable key length and collision-retry loop, replacing deterministic Hashids keyed to a DB sequence.
- **Full CRUD API for short URLs:** `GET`, `PUT`, `DELETE` on `/url-shortener/v3/short-url/:key`.
- **Per-tenant configuration:** `GET/POST /url-shortener/v3/config` and `PUT /url-shortener/v3/config/:id` for tenant-specific settings (key length, allowed domains, default validity).
- **Bidirectional PubSub:** 4 typed event topics (created, updated, deleted, resolved) over Kafka or Redis Streams, runtime-selectable.
- **HTTP 307 redirect** (method-preserving) replaces 302.

> Carried over (parity): short URL creation and redirect resolution, `validityInDays` metadata storage, multi-tenancy.

**Behavior changes to watch:** Key generation switched from deterministic Hashids (DB sequence) to cryptographically random base62 — backward compatibility for existing hashid-format keys is not guaranteed.

---

## 3. API Changes

v2.9 had a minimal two-endpoint surface. v3.0 adds a full CRUD and per-tenant config surface under `/url-shortener/v3`.

| Concern | v2 endpoint(s) | v3 endpoint(s) |
|---|---|---|
| Create short URL | `POST /egov-url-shortening/shortener` (returns plain string) | `POST /url-shortener/v3/short-url` (returns JSON object; enforces validity window) |
| Redirect / resolve | `GET /egov-url-shortening/{id}` (HTTP 302) | `GET /url-shortener/:key` (HTTP 307; rejects expired keys) |
| Fetch record | *(none)* | `GET /url-shortener/v3/short-url/:key` |
| Update record | *(none)* | `PUT /url-shortener/v3/short-url/:key` |
| Delete record | *(none)* | `DELETE /url-shortener/v3/short-url/:key` |
| Tenant config | *(none)* | `GET/POST /url-shortener/v3/config`, `PUT /url-shortener/v3/config/:id` |
| DB migration | *(none)* | `POST /url-shortener/v3/_migrate` |

---

## 4. DB Changes

| v2 table | v3 table | Key differences |
|---|---|---|
| `eg_url_shortener` | `url_shortener` | UUID PK replaces VARCHAR PK; separate `key` VARCHAR UNIQUE column; `url` widened from VARCHAR(1024) to TEXT; `valid_from`/`valid_to` TIMESTAMP added and actively enforced; full audit columns added |
| *(none)* | `url_config` | New per-tenant configuration table (key length, allowed domains, default validity) |

Other DB notes: validity columns are now enforced constraints, not inert metadata. Migration tooling changed from Spring Flyway auto-config to an external shell script + tenant-aware migration library.

---
