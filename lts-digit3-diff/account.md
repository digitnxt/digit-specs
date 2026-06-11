# Account (Tenant Management): DIGIT 2.9 (Java) → 3.0 (Go)

**Old:** `tenant-management` (Spring Boot 3.2.2 / Java 17) · DIGIT 2.9

**New:** `account` (Go 1.24 / Gin + GORM) · DIGIT 3.0

Multi-tenant account service: registers tenants and their configuration, and in digit3.0 also provisions tenant identity. This document covers account-specific changes only; changes common to all digit3.0 services are excluded.

---

## 1. Tech Stack & Architecture Changes

| Aspect | digit2.9 (Java) | digit3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.2.2 | Go 1.24, idiomatic layered |
| Web | Spring MVC (Tomcat), port 8083, ctx `/tenant-management` | Gin, port 8094, ctx `/account` |
| DB access | `JdbcTemplate` + hand-built SQL | GORM (pgx v5) |
| Write persistence | Async over Kafka → egov-persister runs the SQL (`202 ACCEPTED`) | In-service GORM transactions (synchronous) + event publish |
| Identity store | External **User service** (`/user/_createnovalidate`) | **Keycloak** realm-per-tenant, provisioned in-service |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go` / `routes.go`, interface-based |

---

## 2. Features Added in digit3.0

- **Self-service signup (OTP-verified)**: new `POST /v3/tenants/registrations` → `/verify` → `/resend`. Step 1 stashes the create payload in Redis keyed by a signed `referenceId`; the tenant is created (active) only after OTP verification. digit2.9 was admin-only direct create.
- **In-service identity provisioning**: each tenant create provisions a **Keycloak realm** and sets the admin password (supplied, or generated + emailed). digit2.9 delegated user creation to the external User service.
- **Hard tenant delete**: new `DELETE /v3/tenants/{tenantId}` removes the realm, all configs, and the tenant row — irreversible. No digit2.9 equivalent.
- **New tenant fields**: `phone, address, city, state, pincode`; optional `password` on create (response carries `passwordGenerated`, never the password).
- **Dependency policy**: Keycloak, OTP, and Redis are **mandatory** (signup/auth fail without them); AccessControl default-rule init and notification setup are **conditional** (off → tenant still created, no default rules / no notification).

> Carried over (parity): tenant + tenant-config CRUD, `code`-based uniqueness, JSONB additional attributes.
>
> Removed: **sub-tenant** CRUD entirely; `tenant/config/_inactive` (least-active-accounts) and `lastLoginTime` tracking.

---

## 3. API Changes

digit2.9 used a flat envelope-style POST API (`_create`/`_search`/`_update`) under `/tenant-management`. digit3.0 uses **versioned REST** under `/account/v3` with proper verbs and `GET`-based search (query filters + `page/size/hasMore`). **Not backward compatible** — every path, verb, and payload changed.

| Concern | digit2.9 endpoint(s) | digit3.0 endpoint(s) |
|---|---|---|
| Tenant CRUD | `POST /tenant/_create` `/_search` `/_update` | `POST /v3/tenants`, `GET /v3/tenants`, `PUT /v3/tenants/{tenantId}`, `DELETE /v3/tenants/{tenantId}` |
| Tenant config | `POST /tenant/config/_create` `/_search` `/_update` | `POST /v3/config`, `GET /v3/config`, `PUT /v3/config/{configId}` |
| Signup | — | `POST /v3/tenants/registrations` `/verify` `/resend` |
| Sub-tenant | `POST /subTenant/*` | *(removed)* |
| Least-active config | `POST /tenant/config/_inactive` | *(removed)* |

Terminology / behavior shift: POST-only `_action` verbs → REST GET/PUT/DELETE; search bodies → query params + pagination; `tenantId` from body → `X-Tenant-Id` header.

---

## 4. DB Changes

Tables renamed to the `*_v1` convention; tenant-config redesigned from a multi-field record into a per-tenant key/value store; documents dropped.

| digit2.9 table | digit3.0 table | Key differences |
|---|---|---|
| `tenant` | `tenant_v1` | + contact fields, `version`, `passwordGenerated`; dropped unused `tenantId` col; UNIQUE(`code`) |
| `tenant_config` | `tenant_config_v1` | **redesigned** as key/value (`tenantid, configkey, configvalue, description`); UNIQUE(`tenantid, configkey`); the old multi-field columns (otpLength/languages/defaultLoginType) are gone |
| `tenant_config_doc` | *(dropped)* | `tenant_documents_v1` removed entirely — document references gone |
| *(sub-tenant tables)* | *(dropped)* | sub-tenant model removed |

**DB note:** the email-unique constraint was dropped — uniqueness is enforced on `code` only, so one admin email may own multiple tenants.
