# Workflow Service: 2.9 (Java) → 3.0 (Go)

**Old:** `egov-workflow-v2` (Spring Boot 3.4 / Java 17) · v2.9.3  
**New:** `workflow` (Go 1.24+ / Gin + GORM) · DIGIT 3.0

Both are generic, multi-tenant, config-driven state-machine engines that other modules register a workflow definition against and then drive via a transition API. 3.0 is a ground-up Go rewrite, not a port. This document covers only **workflow-specific** changes (platform-wide enhancements common to all 3.0 services are excluded).

---

## 1. Tech Stack & Architecture Changes

| Aspect | 2.9 (Java) | 3.0 (Go) |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.5 | Go 1.24+, idiomatic layered/hexagonal |
| Web | Spring MVC (Tomcat), port 8280, ctx `/egov-workflow-v2` | Gin, port 8080, ctx `/workflow` |
| DB access | Raw `JdbcTemplate` + hand-built SQL | GORM (pgx v5) + raw SQL for hot paths |
| State machine | Hand-coded state-graph traversal | `looplab/fsm` library |
| Write persistence | Async over Kafka → egov-persister runs the SQL | **In-service GORM transactions** (synchronous) + event publish |
| DI / wiring | Spring container (`@Autowired`) | Manual DI in `cmd/server/main.go`, interface-based |

---

## 2. Features Added in 3.0

- **Explicit `is_latest` current-row flag**: both versions are append-only (one `process_instances` row per transition — 2.9 never mutated status in place either). The difference is *how the current state is found*: 2.9 derives it at **read time** with a `max(lastmodifiedtime)`-per-businessId subquery / `DENSE_RANK` window function; 3.0 sets an explicit `is_latest` boolean at **write time** (backed by a partial unique index), removing that subquery from the hot read path. The `history` flag for retrieving the full trail exists in both.
- **Escalation config promoted to a first-class resource**: in 2.9 auto-escalation rules lived in MDMS (`AutoEscalation` master); 3.0 persists them in an `escalation_configs` table with full CRUD API.
- **`init` flag** on the transition request (explicit first-transition / workflow-start signal).
- **`escalated` search filter** + dedicated `GET /v3/auto/_search` to query escalated instances.
- **`requestId` propagation** stored on every workflow entity for traceability.

> Carried over (parity): two SLA clocks (per-state + overall process), inbox / role-derived visibility, history search, SLA-breach auto-escalation, document & multi-assignee support.

---

## 3. API Changes

2.9 used a flat envelope-style API (`_create`/`_update`/`_search`/`_transition`/`_count`) under `/egov-wf`. 3.0 uses a **versioned, code-addressed REST hierarchy** under `/v3` (resources keyed by `processCode` / `stateCode` / `actionCode`, not UUIDs), with proper HTTP verbs.

| Concern | 2.9 endpoint(s) | 3.0 endpoint(s) |
|---|---|---|
| Define workflow | `POST /businessservice/_create` `/_update` `/_search` (+ parallel `/v2/*` stack) | `POST/GET/PUT/DELETE /v3/process/definition[/:processCode]`; granular `/v3/process/:code/state[/:code]/action[/:code]` CRUD |
| Transition | `POST /process/_transition` | `POST /v3/transition` (adds `init`, optimistic `currentState`) |
| Search / inbox | `POST /process/_search`, `/_count`, `/_statuscount`, `/_nearingslacount` | `GET /v3/transition` (filters: entityId+history, currentState, assignee, **escalated**, or inbox mode; paginated) |
| Auto-escalate | `POST /auto/{businessService}/_escalate`, `/_test` | `POST /v3/auto/:processCode/_escalate` |
| Escalated search | `POST /escalate/_search` | `GET /v3/auto/_search` |
| Escalation config | MDMS-driven (`AutoEscalation` master) | **First-class API**: `/v3/process/:code/escalation[/:stateCode]` CRUD + `escalation_configs` table |

Terminology shift: **BusinessService → Process**, **businessId → entityId**, **status (state uuid) → code-based state**; verbs now proper GET/PUT/DELETE instead of POST-only.

---
## 4. DB Changes

Complete schema redesign — table names dropped the `eg_wf_*` / `_v2` convention, and escalation config moved out of MDMS into a real table.

| 2.9 table | 3.0 table | Key differences |
|---|---|---|
| `eg_wf_businessservice_v2` | `processes` | + `version`; UNIQUE(`tenant_id`,`code`) |
| `eg_wf_state_v2` | `states` | `is_initial` replaces `isstartstate`; code-based identity |
| `eg_wf_action_v2` | `actions` | `roles` now **JSONB** (was CSV string); + `assignee_check` bool |
| `eg_wf_processinstance_v2` | `process_instances` | both append-only (one row per transition); 3.0 adds `is_latest` flag + partial unique index to mark the current row (2.9 finds it via `max(lastmodifiedtime)` subquery); + `attributes` (JSONB); `documents`/`assignees` now JSONB columns (2.9 had separate `eg_wf_document_v2` + `eg_wf_assignee_v2` child tables) |
| `eg_wf_document_v2`, `eg_wf_assignee_v2` | *(folded into JSONB columns)* | No longer separate tables |
| *(MDMS config)* | `escalation_configs` | Escalation now persisted: `state_code`, `escalation_action`, `state_sla_minutes`, `process_sla_minutes` |

Other DB notes: audit columns store epoch-millis; `requestid` added to workflow tables; performance/partial indexes added (e.g. partial unique on `is_latest` to enforce one current row per entity). An early `attribute_validations` table was **dropped** in favor of `actions.roles` + `actions.assignee_check`.

---
