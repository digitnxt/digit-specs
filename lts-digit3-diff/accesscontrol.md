# Access Control: DIGIT 2.9 (Java) → 3.0 (Go)

**Old:** `egov-accesscontrol` (Spring Boot 3.4.5 / Java 17) · DIGIT 2.9

**New:** `accesscontrol` (Go 1.24 / Gin + GORM) · DIGIT 3.0 

Platform access-control service. digit2.9 was a runtime **enforcement** engine: it answered "may this user perform this action?" per request by loading roles/actions/role-action mappings from MDMS and evaluating them against the tenant hierarchy. digit3.0 inverts the model into a pure **rule store** — it persists authorization rules, and the **Kong** API gateway polls and enforces them at the edge; the service no longer authorizes traffic itself. This document covers accesscontrol-specific changes only; changes common to all digit3.0 services are excluded.

---

## 1. Tech Stack & Architecture Changes

| Aspect | digit2.9 (Java) | digit3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.24, idiomatic layered |
| Web | Spring MVC (Tomcat), port 8280, ctx `/access` | Gin, port 8080, ctx `/access` |
| DB access | `JdbcTemplate` + hand-built SQL | GORM (pgx v5) |
| Write persistence | Synchronous (Spring JDBC) | In-service GORM transactions (synchronous) |
| Authorization model | Runtime **enforcement** in-service; rules read live from **MDMS** | Pure **rule store** (Postgres); enforcement delegated to the **Kong** gateway |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go` / `routes.go`, interface-based |

---

## 2. Features Added in digit3.0

- **JBAC (jurisdiction/attribute-based access)**: a second rule class alongside RBAC, persisted in its own table with full CRUD under `/v3/jbac/rules`. Rules carry a `path_pattern`, HTTP `methods`, an `enforcement` mode, a `parent_implies_children` flag, and a JSONB `extract_jurisdiction` spec — enabling hierarchy-aware, attribute-scoped authorization that digit2.9 (role→action only) could not express.
- **Internal gateway-poll endpoints**: unauthenticated `GET /v3/internal/{rbac,jbac}/rules` return all rules cross-tenant for the gateway to cache, and `/rules/version` returns a **content hash** of the full rule set so the gateway can detect changes and invalidate its cache without re-pulling every rule.
- **Bulk rule creation**: `POST /v3/{rbac,jbac}/rules/bulk` inserts many rules in one call (digit2.9 created role-action mappings one request at a time).
- **Dependency policy**: Postgres is **mandatory** (no rules stored or served without it); the service makes **zero outbound service calls** — a deliberate break from digit2.9's live MDMS reads.

> Carried over (parity): role-based authorization (now expressed as RBAC rules), multi-tenant scoping.
>
> Removed: runtime **enforcement** — `/v1/actions/_authorize` (allow/deny decision) and `/v1/actions/_validate` are gone (enforcement now happens at the gateway); the **MDMS dependency** entirely; and role/action/role-action **CRUD** — digit3.0 stores rules only, not the role/action catalog.

---

## 3. API Changes

digit2.9 exposed flat envelope-style POST endpoints (`_create`/`_update`/`_search`/`_get`/`_list`) under `/access/v1`, plus the enforcement verbs `_authorize` and `_validate`. digit3.0 uses **versioned REST** under `/access/v3` with proper verbs, `GET`-based search, and `X-Tenant-ID` / `X-User-ID` headers (writes require both).

| Concern | digit2.9 endpoint(s) | digit3.0 endpoint(s) |
|---|---|---|
| RBAC rules | `POST /v1/role-actions/_create`; roles/actions via `/v1/roles/*`, `/v1/actions/*` (MDMS-backed) | `GET/POST /v3/rbac/rules`, `POST /v3/rbac/rules/bulk`, `GET/PATCH/DELETE /v3/rbac/rules/{ruleId}`, `DELETE /v3/rbac/rules/tenant` |
| JBAC rules | — | `GET/POST /v3/jbac/rules`, `POST /v3/jbac/rules/bulk`, `GET/PATCH/DELETE /v3/jbac/rules/{ruleId}`, `DELETE /v3/jbac/rules/tenant` |
| Gateway / internal | — | `GET /v3/internal/{rbac,jbac}/rules` + `/rules/version` (gateway poll + cache hash) |
| Authorize / validate | `POST /v1/actions/_authorize`, `/v1/actions/_validate` | *(removed — enforcement at the gateway)* |
| Role / action catalog | `/v1/roles/*`, `/v1/actions/*`, `/v1/actions/mdms/_get` | *(removed — no longer this service's concern)* |

Terminology / behavior shift: in-service **authorization decision → externalized rule polling**; **MDMS-sourced roles/actions → Postgres-persisted rules**; POST-only `_action` verbs → REST GET/POST/PATCH/DELETE; `tenantId` in body → `X-Tenant-ID` header.

---

## 4. DB Changes

digit2.9 stored the role/action catalog and mappings in four legacy tables (the authoritative copy lived in MDMS). digit3.0 replaces them with two purpose-built rule tables; authorization is now authored directly as rules rather than derived from an MDMS catalog.

| digit2.9 table | digit3.0 table | Key differences |
|---|---|---|
| `eg_roleaction` | `access_rbac_rules_v3` | rule model: `role_names[]`, `http_method`, `path`, `effect`, `priority`, `enabled`, JSONB `constraints`; per-tenant; UUID PK |
| *(none)* | `access_jbac_rules_v3` | new: `path_pattern`, `methods[]`, `enforcement`, `parent_implies_children`, JSONB `extract_jurisdiction` |
| `eg_ms_role`, `eg_action`, `service` | *(dropped)* | role/action/service catalog no longer persisted here (was MDMS-backed) |
