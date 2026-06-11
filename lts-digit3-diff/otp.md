# OTP: DIGIT 2.9 (Java) → 3.0 (Go)

**Old:** `egov-otp` (Spring Boot 3.4.5 / Java 17) · DIGIT 2.9 

**New:** `otp` (Go 1.24 / Gin + GORM) · DIGIT 3.0 

One-time-password service for other DIGIT modules. digit2.9 was a thin token store that minted a code and returned it to the caller; digit3.0 owns the full OTP lifecycle — per-tenant policy, dispatch, and verification. This document covers otp-specific changes only; changes common to all digit3.0 services are excluded.

---

## 1. Tech Stack & Architecture Changes

| Aspect | digit2.9 (Java) | digit3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.24, idiomatic layered |
| Web | Spring MVC (Tomcat), port 8089, ctx `/otp` | Gin, port 8080, ctx `/otp` |
| DB access | `JdbcTemplate` + hand-built SQL | GORM (pgx v5) |
| Write persistence | Synchronous `JdbcTemplate` save | In-service GORM transactions + event publish |
| Cache | None | **Redis** (rate-limit, lockout, idempotency, config cache) |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go`, interface-based |

---

## 2. Features Added in digit3.0

- **Full OTP lifecycle**: beyond generate/verify, digit3.0 adds `/invalidate` (revoke on logout/security event) and `/resend` (cooldown- and hourly-limited reissue). digit2.9 had only create, validate, and search.
- **Per-(tenant, purpose) config**: a new `purpose` concept (login / transaction / forgot-password / etc.) drives policy — OTP length, TTL, cooldown, max attempts, lockout, hourly generate/resend caps — managed via `/v3/config` CRUD and cached in Redis. digit2.9 had a single global length/TTL.
- **Redis rate-limiting & lockout**: per-destination and per-IP hourly generate caps, verify-attempt lockout, and `(tenant, destination, purpose)` idempotency on `/generate`. None existed in digit2.9.
- **Always-HMAC storage**: the code is stored only as an **HMAC-SHA256** hash (`otp_hash`), verified in constant time; plaintext-at-rest is gone. digit2.9 stored the code plaintext or, when `egov.otp.encrypt=true`, as a bcrypt hash.
- **Destination encryption**: the OTP destination (email/phone) is encrypted at rest via **Vault Transit (AppRole)**, a **mandatory** dependency; a separate HMAC `destination_hash` is kept for indexed lookup.
- **Distinct verify status codes**: `410` expired / `422` mismatch / `423` locked, vs digit2.9's single generic validation failure.

> Carried over (parity): generate, verify, identity/destination + tenant scoping, configurable length/TTL.
>
> Removed: the OTP value is **no longer returned** from generate (digit2.9 `_create` returned the code in the response); the bare `eg_token` model and the `/_search` lookup are gone.

---

## 3. API Changes

digit2.9 exposed three flat POST verbs under `/otp` (`v1/_create`, `v1/_validate`, `v1/_search`), with `_create` returning the OTP value inline. digit3.0 moves to `/otp/v3`: a self-detected `identifier` (email/phone) plus `purpose` drive generation, `/generate` returns only a `referenceId` and dispatches the code via the Notification service, and verify keys off that `referenceId`. **Not backward compatible** — every path, verb, and payload changed.

| Concern | digit2.9 endpoint(s) | digit3.0 endpoint(s) |
|---|---|---|
| Generate | `POST /otp/v1/_create` (returns OTP) | `POST /otp/v3/generate` (returns `referenceId`, dispatches) |
| Verify | `POST /otp/v1/_validate` | `POST /otp/v3/verify` (`410`/`422`/`423` codes) |
| Lookup | `POST /otp/v1/_search` | *(removed)* |
| Lifecycle | — | `POST /otp/v3/invalidate`, `POST /otp/v3/resend` |
| Config | — | `POST/GET/PUT/DELETE /otp/v3/config` |

Terminology / behavior shift: `_action` POST verbs → `/v3` action endpoints (config gets full REST CRUD); `identity` → auto-detected `identifier` + `purpose`; `tenantId` from body → `X-Tenant-Id` header; OTP-in-response → out-of-band dispatch + `referenceId`.

---

## 4. DB Changes

The single `eg_token` table is replaced by a three-table model — record store, audit trail, and per-tenant config.

| digit2.9 table | digit3.0 table | Key differences |
|---|---|---|
| `eg_token` | `otp_records` | UUID id, `tenant_id`, `purpose`, `status`, `attempts`, `resend_count`, `last_sent_at`, `destination_hash` + optional `destination_encrypted`, `otp_hash` (HMAC), `metadata` JSONB; expiry as an `expires_at` timestamp (was `ttlsecs`) |
| *(none)* | `otp_audit` | new append-only event log (`event`, `details` JSONB, `request_id`) |
| *(none)* | `otp_config` | new per-`(tenant_id, purpose)` policy: length, TTL, cooldown, max attempts, lockout, hourly caps; UNIQUE(`tenant_id, purpose`) |

A composite index on `otp_records(tenant_id, destination_hash, purpose, status, expires_at)` backs the active-OTP and idempotency lookups.
