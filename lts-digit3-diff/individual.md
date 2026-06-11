# Individual: DIGIT 2.9 (Java) → 3.0 (Go)

**Old:** `individual` (Spring Boot 3.2.2 / Java 17) · DIGIT 2.9

**New:** `individual` (Go 1.24 / Gin + GORM) · DIGIT 3.0

Registry of persons with encrypted PII data. This document covers individual-specific changes only; changes common to all digit3.0 services are excluded.

---

## 1. Tech Stack & Architecture Changes

| Aspect | digit2.9 (Java) | digit3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.2.2 | Go 1.24, idiomatic layered |
| Web | Spring MVC (Tomcat), port 8080, ctx `/individual` | Gin, port 8999, ctx `/individuals/v3` |
| DB access | `JdbcTemplate` + hand-built SQL | GORM (pgx v5) |
| Write persistence | Async over Kafka → egov-persister runs the SQL (`202 ACCEPTED`) | In-service GORM transactions (synchronous) + event publish |
| PII encryption | Central **egov encryption-service** (role/purpose-gated) | **HashiCorp Vault** Transit (AppRole), called in-service |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go`, interface-based |

---

## 2. Features Added in digit3.0

- **Existence check**: new `GET /v3/individuals/exists` — lightweight presence test without returning full records.
- **Per-tenant validation config**: new Config resource (`POST`/`GET /v3/configs`) holding `mobileRegex`, `nameRegex`, and `uniquenessCriteria`. digit2.9 had a single global Aadhaar pattern from properties.
- **Optimistic locking**: a client-supplied `version` is checked on update; a stale value is rejected as a conflict (`409 ROW_VERSION_MISMATCH`). digit2.9 had no version guard.
- **Indexed mobile search**: `hashedmobilenumber` (SHA-256) lets exact-match mobile search hit an index without decrypting ciphertext.
- **PII encryption**: PII data ( `mobileNumber`, `altContactNumber`,  `Aadhaar` ,etc) are encrypted at rest via Vault Transit.
- **Dependency policy**: Vault is **mandatory** (PII encrypt/decrypt); IDGen and other integrations are **conditional**.

> Carried over (parity): individual + address + identifier records, multi-tenant scoping.
>
> Removed: all **bulk** endpoints (`/v1/bulk/_create|_update|_delete`); `_mapIndividualToUser`; **User-service** integration and **SMS** notification; the `individual_skill` table; the embedded system-user fields (`isSystemUser`/`username`/`password`/`type`/`roles`/`userUuid`); the fixed 12-digit Aadhaar-format check (now tenant-config-driven).

---

## 3. API Changes

digit2.9 exposed a flat envelope-style POST API (`_create`/`_search`/`_update`/`_delete` plus `bulk` variants and `_mapIndividualToUser`) under `/individual/v1`, returning `202`. digit3.0 uses **versioned REST** under `/v3/individuals` with proper verbs, `GET`-based search (query filters + pagination), and soft delete.

| Concern | digit2.9 endpoint(s) | digit3.0 endpoint(s) |
|---|---|---|
| Create | `POST /v1/_create` | `POST /v3/individuals` |
| Update | `POST /v1/_update` | `PUT /v3/individuals/{individualId}` |
| Delete | `POST /v1/_delete` | `DELETE /v3/individuals/{individualId}` (soft delete) |
| Search / get | `POST /v1/_search` | `GET /v3/individuals` (query + pagination), `GET /v3/individuals/{individualId}` |
| Existence | — | `GET /v3/individuals/exists` |
| Config | — | `POST`/`GET /v3/configs` |
| Bulk | `POST /v1/bulk/_create` `/_update` `/_delete` | *(removed)* |
| User mapping | `POST /v1/_mapIndividualToUser` | *(removed)* |

Terminology / behavior shift: POST-only `_action` verbs → REST GET/PUT/DELETE; search bodies → query params + pagination; hard delete → soft delete; `tenantId` from body → `X-Tenant-ID` header; identity-mapping responsibility removed from this service.

---

## 4. DB Changes

Tables moved to the `*_v3` convention; address becomes a many-to-many join; the skill table and embedded system-user columns are dropped.

| digit2.9 table | digit3.0 table | Key differences |
|---|---|---|
| `individual` | `individual_v3` | + `hashedmobilenumber` (indexed search), `rowversion` (optimistic lock); soft delete via `active`; dropped system-user columns (`isSystemUser`/`username`/`password`/`type`/`roles`/`userUuid`) |
| `individual_address` | `individual_address_v3` + `individual_address_join_v3` | address is now a standalone record joined **many-to-many** (was a direct child of individual) |
| `individual_identifier` | `individual_identifier_v3` |  `identifier` encrypted via Vault; format is per-tenant config, not a fixed 12-digit check |
| `individual_skill` | *(dropped)* | skill registry removed entirely |
| *(none)* | `individual_document_v3`, `individual_config_v3` | new document store + per-tenant config table |
