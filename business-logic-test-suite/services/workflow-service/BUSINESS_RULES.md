# Business Rules — Workflow Service

## Cross-Field Rules

### BR-CF-001: Process code must match pattern

**Entities involved:** Process (`code`)  
**Rule:** `code` must be non-empty, max 128 characters, valid UTF-8, no control characters, and must match the pattern `^[A-Za-z0-9_.:/@+\- ]+$`.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CF-002: Parallel and join states are mutually exclusive

**Entities involved:** State (`isParallel`, `isJoin`)  
**Rule:** A state cannot have both `isParallel = true` AND `isJoin = true`; they are mutually exclusive roles.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CF-003: Parallel state must define non-empty branchStates

**Entities involved:** State (`isParallel`, `branchStates`)  
**Rule:** If `isParallel = true`, the `branchStates` array must be non-empty and contain state codes that exist in the same process. If `isParallel = false`, `branchStates` must be null or empty.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CF-004: Action source and target states must differ

**Entities involved:** Action (`currentState`, `nextState`)  
**Rule:** `currentState` and `nextState` must reference different states; a self-loop transition (same state) is not permitted.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CF-005: Escalation config requires at least one SLA

**Entities involved:** EscalationConfig (`stateSlaMinutes`, `processSlaMinutes`)  
**Rule:** At least one of `stateSlaMinutes` or `processSlaMinutes` must be provided and non-null when creating or updating an escalation config.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CF-006: Action RBAC roles and assigneeCheck control

**Entities involved:** Action (`roles`, `assigneeCheck`), ProcessInstance (`assignees`)  
**Rule:** During a transition, if `action.roles` is non-empty, the requesting user must have at least one of the listed roles. If `action.assigneeCheck = true`, the requesting user must also appear in the instance's `assignees` array. An empty `roles` array permits any authenticated user.  
**Violation response:** 403 — `FORBIDDEN`

---

## Cross-Schema Rules

### BR-CS-001: State requires existing process

**Entities involved:** State, Process  
**Rule:** `State.processId` must reference an existing Process. States cannot be created for non-existent processes.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-002: Action states must belong to same process

**Entities involved:** Action, State, Process  
**Rule:** Both `currentState` and `nextState` in an action must reference existing states that belong to the same process. States from different processes cannot be joined in a single action.  
**Violation response:** 404 — `NOT_FOUND` (if state absent); 422 — `UNPROCESSABLE_ENTITY` (if states from different processes)

---

### BR-CS-003: Escalation config requires existing process

**Entities involved:** EscalationConfig, Process  
**Rule:** `EscalationConfig.processId` must reference an existing Process.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-004: Instance requires existing process

**Entities involved:** ProcessInstance, Process  
**Rule:** `ProcessInstance.processId` must reference an existing Process. Transitions cannot be created for non-existent processes.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-005: Transition action must be valid for current state

**Entities involved:** ProcessInstance, Action, State  
**Rule:** When executing a transition, the action must exist as a defined action where `currentState` equals the instance's current state. Performing an action not defined for the current state is rejected.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### BR-CS-006: Process code uniqueness per tenant

**Entities involved:** Process  
**Rule:** `(tenant_id, code)` must be unique. Two processes in the same tenant cannot share a code.  
**Violation response:** 409 — `CONFLICT`

---

### BR-CS-007: State code uniqueness within process

**Entities involved:** State  
**Rule:** `(process_id, code)` must be unique. Two states within the same process cannot share a code.  
**Violation response:** 409 — `CONFLICT`

---

### BR-CS-008: Escalation config uniqueness per state per process

**Entities involved:** EscalationConfig  
**Rule:** `(tenant_id, process_id, state_code)` must be unique. Only one escalation config per state per process per tenant.  
**Violation response:** 409 — `CONFLICT`

---

### BR-CS-009: Cannot delete process with existing instances

**Entities involved:** Process, ProcessInstance  
**Rule:** A Process cannot be deleted if any `process_instances` rows reference it (`ON DELETE RESTRICT`). Deleting a process with active or historical instances is rejected.  
**Violation response:** 409 — `CONFLICT`

---

## Lifecycle Rules

### BR-LC-001: Instance records are append-only

**Entities involved:** ProcessInstance  
**Rule:** Every state transition creates a NEW row in `process_instances`. Existing rows are never updated. Each row is an immutable snapshot enabling a complete audit trail.  
**Violation response:** N/A (enforced by service; caller sees 202)

---

### BR-LC-002: is_latest flag tracks current state atomically

**Entities involved:** ProcessInstance (`isLatest`)  
**Rule:** At most one row per `(tenant_id, entity_id, process_id)` has `is_latest = true`. When a new instance row is created, the new row gets `is_latest = true` and all prior rows for the same entity/process are atomically set to `is_latest = false`.  
**Violation response:** N/A (managed internally)

---

### BR-LC-003: Instance status transitions for parallel joins

**Entities involved:** ProcessInstance (`status`)  
**Rule:** Status transitions: `ACTIVE` → `WAITING_FOR_JOIN` (when a parallel branch reaches the join state but other branches are still active); `WAITING_FOR_JOIN` → `ACTIVE` (when all branches complete and the merged instance is created at the join state).  
**Violation response:** N/A (enforced by service)

---

### BR-LC-004: Parallel execution status is a terminal sequence

**Entities involved:** ParallelExecution (`status`)  
**Rule:** `status` progresses: `ACTIVE` → `WAITING_FOR_JOIN` → `COMPLETED`. The `COMPLETED` state is terminal and cannot be reversed.  
**Violation response:** N/A (managed internally)

---

### BR-LC-005: Escalation keyword sets escalated flag

**Entities involved:** ProcessInstance (`escalated`)  
**Rule:** If the action name or transition comment contains the substring `"escalat"` (case-insensitive), the resulting instance row has `escalated = true`. This flag is immutable once set.  
**Violation response:** N/A (detected and set internally)

---

### BR-LC-006: State deletion cascades to actions

**Entities involved:** State, Action  
**Rule:** Deleting a state cascades to delete all actions where that state is either `currentState` or `nextState` (`ON DELETE CASCADE`).  
**Violation response:** N/A (cascade enforced at DB)

---

## Cross-Module Rules

### BR-CM-001: Tenant isolation via X-Tenant-ID header

**Entities involved:** All entities  
**Rule:** All queries are scoped to the `tenant_id` extracted from the `X-Tenant-ID` header. Requests without this header are rejected.  
**Violation response:** 400 — `BAD_REQUEST` (missing header); implicit empty results if tenant mismatch

---

### BR-CM-002: RBAC guard enforced during every transition

**Entities involved:** ProcessInstance, Action, JWT roles  
**Rule:** During a transition, user roles are extracted from the JWT `realm_access.roles` claim. If `action.roles` is non-empty, the user must have at least one matching role. If `action.assigneeCheck = true`, the user must be present in the instance's `assignees` array. Both checks must pass.  
**Violation response:** 403 — `FORBIDDEN`

---

### BR-CM-003: PubSub events are fire-and-forget

**Entities involved:** Process, State, Action, ProcessInstance, EscalationConfig  
**Rule:** After any successful mutation, an event is published to the configured PubSub topic. If `PUBSUB_ENABLED = false` or the PubSub backend is unavailable, the mutation still succeeds and the event is silently dropped.  
**Violation response:** N/A (caller sees 200/201/202)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-ID` header | `BAD_REQUEST` |
| 400 | Malformed request or missing required field | `BAD_REQUEST` |
| 403 | User lacks required role for action | `FORBIDDEN` |
| 403 | User not in assignees when `assigneeCheck = true` | `FORBIDDEN` |
| 404 | Process / state / action / instance not found | `NOT_FOUND` |
| 409 | Duplicate process code per tenant | `CONFLICT` |
| 409 | Duplicate state code within process | `CONFLICT` |
| 409 | Duplicate escalation config for state | `CONFLICT` |
| 409 | Cannot delete process with existing instances | `CONFLICT` |
| 422 | State is both isParallel and isJoin | `UNPROCESSABLE_ENTITY` |
| 422 | Parallel state with empty branchStates | `UNPROCESSABLE_ENTITY` |
| 422 | Action currentState equals nextState | `UNPROCESSABLE_ENTITY` |
| 422 | Escalation config missing both SLA thresholds | `UNPROCESSABLE_ENTITY` |
| 422 | Action not valid for current state in transition | `UNPROCESSABLE_ENTITY` |
| 500 | Database or server error | `INTERNAL_SERVER_ERROR` |
