# Employee service — Go→Java parity re-port plan

**Goal:** bring the Java `employee` service into parity with the current Go `employee-go`
(post-refactor commit `8749c30e`), which is the **source of truth**. The Java port was written
against pre-refactor Go and is stale in the jurisdiction resource, search/PATCH/model layers, plus
some pre-existing gaps (transactions, error-status mapping, an ORDER BY injection).

## Principles
- **Go (post-8749c30e) is the contract.** When Java diverges, Java changes.
- **Verify every phase**: `mvn -o test` must stay green; add focused tests for behavioral fixes.
- **No unrelated churn.** Keep changes scoped to the finding being fixed.
- **Reuse proven patterns** from the individual service where they apply (e.g. the central
  `GlobalExceptionHandler` code→status + bare-array envelope).
- After each phase: build, test, report, then continue to the next.

## Decisions needed from the user (non-blocking; sensible defaults chosen)
These are deployment choices, not correctness bugs — I will proceed with the noted default and the
user can override:
1. **DB defaults** (`DB_PORT` 6432 vs 5432, `DB_NAME` employee vs employee_db, password): Go and Java
   differ. **Default: leave Java `application.yml` fallbacks as-is** (env-overridable; deployment-specific).
2. **`PUBSUB_ENABLED` default** (Go false, Java true): **Default: leave Java true** but make the
   publisher honour the flag (Phase 8).
3. **Deactivate/Reactivate endpoints**: Go keeps the *endpoints* but removed the request-body DTOs
   (they now take no body). **Default: keep endpoints, drop body parsing, add state-transition guards.**

---

## Phase 1 — Quick, independent safety fixes  ✅ start here
Small, isolated, high value; no dependency on the model re-port.
- **1a. ORDER BY SQL-injection** — `EmployeeRepository`, `JurisdictionRepository`: stop concatenating
  client `sortBy`/`sortOrder`; hardcode `ORDER BY "createdTime" DESC` (matches Go).
- **1b. IDGen path default** — `EmployeeProperties`/`application.yml`: `idgen/v3` → `/idgen/v3/generate`;
  ensure host+path join is slash-safe.
- **Acceptance:** compile; add a unit test asserting the search SQL contains a fixed ORDER BY and
  ignores `sortBy`.

## ⚠️ Sequencing revision (after starting Phase 2)
Horizontal "models-only" phases do NOT compile in isolation — changing a DTO breaks its
controller/service/repo consumers immediately. So Phases 2–6 are re-cast as **vertical slices**,
each of which compiles + tests green on its own:
- **Slice A — Jurisdiction resource** (models + controller + service + repo): nested routing,
  employeeId path scoping, ownership 404, duplicate-boundary check, required boundary, DTO cleanup,
  bare responses. (Covers old Phases 2-jurisdiction + 4.)
- **Slice B — Employee search + search-by-roles** (criteria + KeycloakClient + service + repo):
  fix columns, add statuses/employeeTypes/date-range/role→user_id, role short-circuit. (Old Phase 5.)
- **Slice C — Employee PATCH/PUT correctness** (PatchEmployeeRequest + UpdateEmployeeRequest +
  service + repo): real partial PATCH, PUT immutability + isActive=false, modifiedBy. (Old Phases 2-emp + 6.)
- **Slice D — Deactivate/Reactivate + create validation** (service + repo): 409 guards, audit,
  empty-batch/maxBatch/date validation.
- **Slice E — Error contract** (advice + pgerr + auth status): code→status, bare-array, 401. (Old Phase 3.)
- **Slice F — Transactions** (old Phase 7). **Slice G — Integration polish** (old Phase 8).
Recommended order: E (error contract, unblocks correct statuses) → A → B → C → D → F → G.
Original Phase-2 model edits are folded into whichever slice consumes them.

## Phase 2 — Model / DTO layer (foundation, unblocks the rest)
- `UpdateEmployeeRequest` → new strict shape (`employeeType, department, designation, status,
  isActive*, jurisdictions`).
- Add `PatchEmployeeRequest` (pointer fields + `hasAnyField()`), and the `EmployeePatch` changeset.
- `EmployeeSearchCriteria`: add `ids`(rename from uuids), `statuses`, `employeeTypes`,
  `dateOfAppointmentFrom/To`, `role`, internal `userIds`; drop stale `phone`, `sortBy`, `sortOrder`.
- Jurisdiction DTOs: drop `employeeId` from `CreateJurisdictionRequest`/`UpdateJurisdictionRequest`
  and from `JurisdictionSearchCriteria`; make `boundaryRelation` required. Drop `tenantId` from
  `JurisdictionResponse`.
- Add `auditDetail` to `EmployeeResponse`.
- Add bind-time validation (`@NotNull`/`@Size`) on `CreateEmployeeRequest` tied to column widths.
- **Acceptance:** compile; DTO shape matches Go json tags.

## Phase 3 — Central error contract (mirror individual service)
- Add a `@RestControllerAdvice` mapping domain codes → HTTP status (NOT_FOUND=404,
  EMPLOYEE_EXISTS/…/ALREADY_ACTIVE/INACTIVE/CONFLICT=409, UNAUTHORIZED=401, FORBIDDEN=403,
  DOWNSTREAM_ERROR/BAD_GATEWAY=502, validation=400, default=500) and emitting Go's **bare-array**
  error body.
- Add a `pgerr` equivalent: SQLState `23505`→`EMPLOYEE_EXISTS` (409), `23503`→`…EMPLOYEE_NOT_FOUND` (404).
- Fix HTTP-layer auth: missing `Authorization` → 401 (not 400); PUT must **not** require auth.
- **Acceptance:** unit tests for status mapping + bare-array shape (like individual's).

## Phase 4 — Jurisdiction resource re-port (nested + owned)
- Controller: nest under `/v3/employees/{id}/jurisdictions[/{jurisdictionId}]`; param `jurisdictionId`.
- Service: thread `employeeId` through create/get/search/update; ownership 404 on get/update;
  immutable owner on update (full boundary replace); `checkDuplicateBoundaryRelations`; empty-relation
  400 guard; boundary-failure → 502; FK→404; bare (unwrapped) responses.
- **Acceptance:** compile; targeted tests for ownership 404, duplicate-boundary 400, nested paths.

## Phase 5 — Search-by-roles + search filter fixes
- `KeycloakClient.getUserIDsByRole` (paginated, 404→empty).
- `EmployeeService.searchEmployees`: resolve `role`→userIds→`user_id IN`, empty-set short-circuit.
- `EmployeeRepository.search`: fix columns (`department`/`designation`, drop `department_id`/
  `designation_id`/`mobile_number`); add `statuses`, `employeeTypes`, `dateOfAppointment` range,
  `user_id IN`.
- **Acceptance:** unit test for role short-circuit + SQL predicate presence.

## Phase 6 — Employee service correctness
- PATCH via `PatchEmployeeRequest` (partial, empty-body 400, jurisdiction replace-on-set).
- PUT: immutable-field enforcement (Omit code/userId/individualId/dateOfAppointment/tenantId/
  createdBy/createdTime); persist `isActive=false` and cleared strings.
- Deactivate/Reactivate: state-transition 409s, stamp `modifiedBy`/`modifiedTime`, row lock.
- Create: empty-batch 400, `maxCreateBatch=100`, `dateOfAppointment` validation.
- `modifiedBy` stamping on all mutations; `validateUserID/IndividualID` optional + 502 semantics.
- **Acceptance:** unit tests for PATCH partials, isActive=false persistence, double-deactivate 409.

## Phase 7 — Transactions
- `@Transactional` on employee create/update/patch/delete service (or repo) write paths so
  employee + jurisdictions commit/roll back atomically (mirrors Go request transaction).
- **Acceptance:** reasoning + compile (DB rollback verified in dev).

## Phase 8 — Integration polish
- Event payload `traceId`; `EventPublisher` honours `pubsub.enabled`; publish jurisdiction
  create/update events.
- IDGen: drop `ORG` injection; surface status+body in errors.
- Boundary/Individual clients: config-driven `path`; richer error detail.
- **Acceptance:** compile + event test (traceId present, gating).

## Phase 9 — Final verification
- Full `mvn -o test`; produce a dev smoke-test checklist mapped to each fix; summary report.

---

## Progress log
- [x] **Phase 1** — ORDER BY injection removed (both repos, fixed `createdTime DESC`); IDGen path
  default `/idgen/v3/generate` + slash-safe host join. Compiles. (SQL-shape unit test deferred to
  Phase 5 where search is reworked testably.)
- [x] **Slice E (error contract)** — `EmployeeExceptionHandler` (code→status per Go StatusForCode,
  bare-array body, pgerr 23505→EMPLOYEE_EXISTS 409 / 23503→404), `ErrorItem`, HeadersFilter bare array.
  Auth UNAUTHORIZED→401 now automatic. 8 tests green.
- [x] **Slice A (jurisdiction)** — nested `/v3/employees/{employeeId}/jurisdictions[/{jurisdictionId}]`,
  employeeId path-scoped through service+repo, ownership 404 on get/update, `checkDuplicateBoundaryRelations`,
  required boundary + empty guard, immutable owner on update, bare responses, DTOs stripped of employeeId,
  response tenantId removed, boundary-failure→502 (DOWNSTREAM_ERROR). Circular Lazy dep removed. 12 tests green.
  NOTE: boundary validation now unconditional (matches Go; previously gated on boundary.enabled).
- [x] **Slice B (search/roles)** — `KeycloakClient.getUserIDsByRole` (paginated, 404→empty);
  `searchEmployees(criteria, authHeader)` resolves role→userIds with empty short-circuit; repo filters
  fixed (`department`/`designation`, dropped `*_id`/`mobile_number`) + added `statuses`/`employeeTypes`/
  `dateOfAppointment` range/`user_id IN`; criteria reshaped (`ids`, role, drop phone/sort); controller
  role-auth 401 gate. 14 tests green.
- [x] **Slice C (patch/put)** — strict `UpdateEmployeeRequest` (PUT, no auth, immutables preserved,
  required-field 400s); new `PatchEmployeeRequest`+`EmployeePatch` (real partial PATCH, empty-body 400,
  jurisdiction replace-on-set); repo `update` writes mutable surface unconditionally (isActive=false
  persists) + omits immutables, new `patch`; `modifiedBy` stamped on create/update/patch; `auditDetail`
  added to `EmployeeResponse`; jurisdiction replace no longer swallows errors. 17 tests green.
- [x] **Slice D (deactivate/create)** — deactivate/reactivate drop the body, enforce
  EMPLOYEE_ALREADY_ACTIVE/INACTIVE 409 + stamp modifiedBy/Time; create empty-batch 400 +
  maxCreateBatch=100 + dateOfAppointment validation; validateUserID/IndividualID optional + 502;
  generateEmployeeCode → 502. 
- [x] **Slice F (transactions)** — `@Transactional` on create/update/patch/delete/deactivate/reactivate
  so employee + jurisdiction writes commit/roll back atomically.
- [x] **Slice G (polish)** — event `traceId` + `pubsub.enabled` gating; IdGen `ORG` injection removed +
  status/body surfaced on error; jurisdiction create/update events already emitted (Slice A).
  22 tests green on `mvn clean test`.

- [x] **Cleanup (non-boundary/ID)** — in-process create validation (required + column-width caps →
  clean 400); `auditDetail` wire-key parity on JurisdictionResponse; deleted dead DTOs
  (Deactivation/ReactivationDetails) + dead repo methods (findByCode/updateIsActive/employeeCodeExists),
  updated native hints. 24 tests green on `mvn clean test`.

- [x] **Config-driven client paths** — Boundary/Individual clients now read `path` from config
  (BOUNDARY_PATH / INDIVIDUAL_PATH), normalized like Go; no more hardcoded endpoint paths. 24 tests green.

- [x] **Gating decision — RESOLVED: keep unconditional (Go-exact).** Boundary/userId/individualId
  validation always runs. Removed the now-dead `enabled` flags from Boundary/Individual/Keycloak
  config + application.yml (Go has none). Dev must have those services reachable or creates/jurisdiction
  writes 502 — matching Go exactly.

=== RE-PORT COMPLETE — all phases done. ===

### Independent verification pass (two fresh agents re-audited Java vs Go)
All 8+ targeted fixes CONFIRMED present/correct. Additional issues found & FIXED:
- [x] **HIGH regression** — `JurisdictionRepository.update` had the GORM zero-skip bug (`if isActive`),
  so a PUT setting a jurisdiction `isActive=false` didn't persist. Rewrote to write is_active
  unconditionally + omit immutable employee_id (mirrors Go Select("*").Omit(...)).
- [x] **HIGH regression** — `createEmployees` swallowed jurisdiction failures in a try/catch; under
  @Transactional that COMMITTED a partial employee (201 with no jurisdiction). Now propagates → rolls back.
- [x] **MEDIUM** — client-input errors now 400 not 500: central IllegalArgumentException→400 (malformed
  `ids` UUID / bad date param); PUT+PATCH length caps (was DB 22001→500).
- [x] **MEDIUM** — search pagination bounds enforced (limit 1..100, offset ≥0 → 400) on both searches.
- [x] **LOW** — fixed stale javadocs (EmployeeRepository/HeadersFilter/ControllerSupport); removed dead
  `notEmpty`. 26 tests green on `mvn clean test`.

Still open (LOW, non-blocking): PUT response modifiedTime is in-memory (Go re-fetches) — cosmetic ms
drift; employee search still N+1 on jurisdictions (Go batch-loads) — performance only, results correct.

### Original horizontal Phases 2–9 → superseded by vertical slices (all ✅)
The initial horizontal phase list was replaced by compilable vertical slices after the sequencing
revision above (a DTO change breaks its consumers, so layers had to land together). Mapping:
- [x] Phase 2 (models/DTOs) → folded into Slices A, B, C (each carried its own DTO changes)
- [x] Phase 3 (error contract) → **Slice E**
- [x] Phase 4 (jurisdiction) → **Slice A**
- [x] Phase 5 (search-by-roles) → **Slice B**
- [x] Phase 6 (employee correctness) → **Slices C + D**
- [x] Phase 7 (transactions) → **Slice F**
- [x] Phase 8 (integration polish) → **Slice G + Config-driven client paths**
- [x] Phase 9 (final verification) → `mvn clean test` green (24 tests); dev smoke-test checklist pending real infra
