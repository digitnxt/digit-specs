# Employee (HRMS): DIGIT 2.9 (Java) → 3.0 (Go)

**Old:** `egov-hrms` (Spring Boot 2.2.6 / Java 8) · DIGIT 2.9 

**New:** `employee` (Go 1.24 / Gin + GORM) · DIGIT 3.0 

Employee registry for DIGIT: stores employees, their jurisdiction, and active/inactive lifecycle. In digit3.0 the service scope is reduced to the core employee record and its jurisdictions — the additional HR sub-records digit2.9 maintained (assignments, educational details, departmental tests, documents, service history) are no longer part of the service. This document covers employee-specific changes only; changes common to all digit3.0 services are excluded.

---

## 1. Tech Stack & Architecture Changes

| Aspect | digit2.9 (Java) | digit3.0 (Go) |
|---|---|---|
| Language / runtime | Java 8, Spring Boot 2.2.6 | Go 1.24, idiomatic layered |
| Web | Spring MVC (Tomcat), port 9999, ctx `/egov-hrms` | Gin, port 8080, ctx `/employee` |
| DB access | `JdbcTemplate` + hand-built SQL | GORM (pgx v5) |
| Write persistence | Async over Kafka → egov-persister runs the SQL (`202 ACCEPTED`) | In-service GORM transactions (synchronous, `201 CREATED`) + event publish |
| Identity store | External **User service** (creates user + password) | References an existing **`individualId`** (Individual) + **`userId`** (Keycloak); creates neither |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go`, interface-based |

---

## 2. Features Added in digit3.0

- **Synchronous create (`201`)**: create now persists in-request and returns the saved record with `201`. digit2.9 returned `202` and committed later on a Kafka consumer, so duplicate codes / bad input were dropped silently off-band; digit3.0 surfaces them as a failed HTTP call.
- **Partial update (`PATCH /employees/{employeeId}`)** alongside full-replace `PUT` — update individual fields without resubmitting the whole record. No digit2.9 equivalent (it had only `_update`).
- **Hard delete (`DELETE /employees/{employeeId}`)** — removes the employee row; its jurisdictions cascade-delete via an `ON DELETE CASCADE` FK. Returns `404` if the id is unknown. digit2.9 had no delete endpoint.
- **Explicit state transitions**: `POST /employees/{employeeId}/deactivate` and `/reactivate` toggle `isActive`, returning `409` if the employee is already in the target state. digit2.9 carried deactivation/reactivation as nested record data, not as guarded actions.
- **Per-employee jurisdiction management**: jurisdictions are a nested resource — `POST`/`GET /employees/{employeeId}/jurisdictions` (create, list) and `GET`/`PUT /employees/{employeeId}/jurisdictions/{jurisdictionId}` (read, update one) — managed independently of the employee payload. (No dedicated delete endpoint; jurisdictions clear via employee delete-cascade)

- **Dependency policy**: `individualId` (Individual), `userId` (Keycloak), and boundary validation are **conditional** — when enabled, the reference is validated and rejected if invalid; when disabled, it is accepted without the call. IDGen generates the employee `code` when omitted.

> Carried over (parity): employee + jurisdiction record, `code`-based uniqueness per tenant, active/inactive lifecycle.
>
> Removed: **identity ownership** (no User or password creation — now references an existing `individualId` + `userId`); **MDMS** master-data validation; **notification** (SMS/email) entirely; the child entities **assignments, educational details, departmental tests, documents, service history** (and their tables); `_count` and the name/phone/role/position/`asOnDate` search filters.

---

## 3. API Changes

digit2.9 used a flat envelope-style POST API (`_create`/`_search`/`_update`/`_count`) under `/egov-hrms`, returning `202`. digit3.0 uses a **REST tree** under `/employee` with proper verbs, `GET`-based search (query filters + pagination), and synchronous `201`. **Not backward compatible** — every path, verb, and payload changed.

| Concern | digit2.9 endpoint(s) | digit3.0 endpoint(s) |
|---|---|---|
| Create / update | `POST /employees/_create` `/_update` | `POST /employees` (`201`), `PUT` + `PATCH /employees/{employeeId}` |
| Read / search | `POST /employees/_search` `/_count` | `GET /employees`, `GET /employees/{employeeId}` |
| Delete | — | `DELETE /employees/{employeeId}` |
| Lifecycle | *(fields in `_update` body)* | `POST /employees/{employeeId}/deactivate` `/reactivate` |
| Jurisdiction | *(inline in `_create`/`_update` body)* | `POST/GET /employees/{employeeId}/jurisdictions`, `GET/PUT /employees/{employeeId}/jurisdictions/{jurisdictionId}` |

Terminology / behavior shift: POST-only `_action` verbs → REST GET/PUT/PATCH/DELETE; search bodies → query params + pagination; async `202` → synchronous `201`. Search filters narrowed to `ids`, `codes`, `statuses`, `employeeTypes`, `departments` — digit2.9's `names`, `phone`, `roles`, `positions`, `asOnDate` (and `_count`) are gone.

---

## 4. DB Changes

The eight-table `eg_hrms_*` model collapses to two `*_v3` tables; all child-record tables are dropped.

| digit2.9 table | digit3.0 table | Key differences |
|---|---|---|
| `eg_hrms_employee` | `employee_v3` | UUID PK; UNIQUE(`tenant_id`,`code`); references `individualId` + Keycloak `userId` instead of owning a User row |
| `eg_hrms_jurisdiction` | `employee_jurisdiction_v3` | boundary now a single `boundary_relation` **JSONB** column (was `hierarchy`/`boundarytype`/`boundary` cols); FK → `employee_v3` with `ON DELETE CASCADE` |
| `eg_hrms_assignment`, `…educationaldetails`, `…departmentaltests`, `…empdocuments`, `…servicehistory` | *(dropped)* | child entities removed entirely — assignments, qualifications, tests, documents, and service history no longer modeled |
| `…deactivationdetails`, `…reactivation` | *(dropped)* | lifecycle is now the `isActive` flag + deactivate/reactivate actions |
