# Employee & Jurisdiction — Optimistic Versioning + Jurisdiction Reconcile

**Status:** finalized design, pending implementation.
**Scope:** employee-go, employee-java (both entities: employee + jurisdiction).
**Note:** this intentionally **ignores the current `employee.yaml` spec** (which marks `version`
read-only + pessimistic lock). The spec should be updated to match this design afterward.

---

## 1. Decisions (the "why")

### D1 — Optimistic locking (not pessimistic)
Employee is a **master-data / registry** entity (like individual, account), not a transactional/
financial one (like billing, workflow). Two reasons optimistic wins:
- **No lock held across slow work.** Employee update does Keycloak + Individual + Boundary calls and a
  multi-row jurisdiction reconcile. A pessimistic `SELECT … FOR UPDATE` would hold the row lock across
  all of that → contention/timeouts. Optimistic holds no lock; it checks a version at write time.
- **Consistency with its sibling** individual (and account), which are optimistic.
- **Action:** remove employee-go's `FetchForUpdate` (SELECT … FOR UPDATE); adopt the individual
  `rowversion` compare-and-swap pattern.

### D2 — Independent versions (employee and jurisdiction are NOT aggregated)
individual's children have **no API** → single aggregate root, single version. employee's jurisdiction
has its **own API** (`PUT /employees/{id}/jurisdictions/{jid}`) → it is an independently-addressable
resource → it gets **its own** version, independent of the employee's.
- `PUT/PATCH employee` is guarded by, and bumps, **employee.version**.
- `PUT jurisdiction` is guarded by, and bumps, **jurisdiction.version** — it does **NOT** touch
  employee.version.
- **Independent, but the employee path still checks jurisdiction versions.** "Not aggregated" means
  the two versions are never merged into one number — it does **not** mean the employee-PUT path
  ignores jurisdiction versions. When an employee PUT/PATCH updates a jurisdiction *in place* (item
  carries an `id`), it **must** be sent with that jurisdiction's `version`, which is checked → 409 on
  mismatch (see §3). Rule of thumb: *to touch any existing row — employee or jurisdiction — you must
  present its current version.* The client already has these versions from the GET employee response,
  so it just echoes them back.
- **Residual window (sole one, documented not silent):** the version check can only cover items the
  client *sends*. A full-state PUT that **omits** a jurisdiction deactivates it (per §3) with no
  version check — there is no version to compare against something the client didn't include. This is
  inherent to full-state PUT semantics (omitting a row = "I want it gone") and is far narrower than a
  blind overwrite of an edited row.

### D3 — Reconcile-by-id (not wipe-and-recreate) for jurisdictions on employee PUT/PATCH
Wipe-and-recreate resets every jurisdiction's id, audit, and **version** on each employee update —
which is incompatible with versioning them. Reconcile preserves identity/audit/version. This also
matches how individual reconciles its children.

### D4 — Soft-delete on omit
A jurisdiction left out of a supplied array is **deactivated** (`is_active = false`), not hard-deleted
— matching individual's `deactivateOmitted` and preserving history.

---

## 2. Version semantics per operation

| Operation | Client sends (guard) | On success bumps |
|---|---|---|
| `POST /employees` (create) | — | employee.version = 1; each jurisdiction.version = 1 |
| `PUT /employees/{id}` | **employee.version** | employee.version +1; reconciled jurisdictions per §3 |
| `PATCH /employees/{id}` | **employee.version** | employee.version +1; reconciled jurisdictions per §3 (only if `jurisdictions` supplied) |
| `POST /employees/{id}/deactivate` | — (bodyless; see note) | employee.version +1 |
| `POST /employees/{id}/reactivate` | — (bodyless; see note) | employee.version +1 |
| `POST /employees/{id}/jurisdictions` (create) | — | new jurisdiction.version = 1 |
| `PUT /employees/{id}/jurisdictions/{jid}` | **jurisdiction.version** | that jurisdiction.version +1 (employee.version unchanged) |

- **Version check:** if the sent version != current row version → **409 `ROW_VERSION_MISMATCH`**.
- **Deactivate/Reactivate are bodyless**, so they can't carry a version token. They bump
  employee.version but do **not** perform a version-token check — their concurrency safety comes from
  the existing state-transition guard (`409 EMPLOYEE_ALREADY_ACTIVE/INACTIVE`). *(Sub-decision: keep
  bodyless. If a version token is later wanted here, accept it as a query param.)*

---

## 3. Jurisdiction reconcile (employee PUT/PATCH, when `jurisdictions` is supplied)

Three-way diff against the employee's existing active jurisdictions:

| Array item | Action |
|---|---|
| has `id` + `version`, belongs to this employee | **check version → 409 `ROW_VERSION_MISMATCH` on mismatch**, else **update in place** → bump that jurisdiction.version |
| has `id` **without** `version` | **reject** → 400 (version required to update an existing jurisdiction) |
| no `id` | **insert** new → jurisdiction.version = 1 |
| `id` not owned by this employee | **reject** → 404/400 (anti cross-tenant/employee tampering) |
| existing jurisdiction **omitted** from the array | **deactivate** (`is_active=false`) — see residual-window note |

Notes:
- The employee operation as a whole is guarded by **employee.version**, AND each *in-place* jurisdiction
  update is additionally guarded by **that jurisdiction's version** (item must carry `id` + `version`).
  The two versions stay independent (not merged) — the employee path simply checks both. This closes
  the "silent overwrite of a concurrently-edited jurisdiction" window.
- **Residual window:** the `omitted → deactivate` case cannot be version-checked (nothing was sent to
  compare). A full-state PUT that omits a jurisdiction deactivates it even if it was just edited
  elsewhere. Inherent to full-state PUT ("omitted = remove"); accepted.
- The whole request is transactional: if employee.version OR any supplied jurisdiction.version is
  stale, the entire PUT/PATCH 409s and rolls back (all-or-nothing). The 409 description names the
  offending id.
- Empty array `[]` = "desired set is empty" → deactivate all existing.

---

## 4. Behavior matrix — `jurisdictions` field on employee update

| `jurisdictions` value | PUT (`/employees/{id}`) | PATCH (`/employees/{id}`) |
|---|---|---|
| omitted / null | **400** — required (full-state) | **left untouched** (existing preserved) |
| `[]` (empty) | deactivate all existing | deactivate all existing |
| `[ {...}, … ]` | **reconcile** per §3 | **reconcile** per §3 |

Only the *omitted* case differs between PUT and PATCH.

---

## 5. API contract changes (deviates from current spec — update spec after)

Responses (already in spec, readOnly):
- `EmployeeResponse.version` (int32)
- `JurisdictionResponse.version` (int32)

Requests (NEW — needed so the client can send the concurrency token):
- `UpdateEmployeeRequest.version` (int32, required)
- `PatchEmployeeRequest.version` (int32, required)
- `UpdateJurisdictionRequest.version` (int32, required)
- Create requests: **no** version (server sets 1).
- Jurisdiction items inside an employee PUT/PATCH body: `id` + `version` **required together** to update
  an existing jurisdiction (checked → 409 on mismatch); an item with neither `id` nor `version` is a
  new insert (v1). `id` without `version` → 400.

Errors: `409 ROW_VERSION_MISMATCH` (already a code; maps to 409).

---

## 6. DB migration (employee-go/db/migrations, new file)
```sql
ALTER TABLE employee_v3              ADD COLUMN version integer NOT NULL DEFAULT 1;
ALTER TABLE employee_jurisdiction_v3 ADD COLUMN version integer NOT NULL DEFAULT 1;
```
(Column named `version` — same word as entity field and DTO, no mapping. Note: individual uses
`rowversion`; employee deliberately uses `version` end-to-end.)
(Existing rows default to 1. Java/Go share the same schema; the migration is run out-of-band, as today.)

---

## 7. Implementation checklist (mirror in Go + Java)

**Models / entities**
- [ ] `Employee.rowVersion` (int), `Jurisdiction.rowVersion` (int) + row-mapper `rowversion` column.

**DTOs**
- [ ] `EmployeeResponse.version`, `JurisdictionResponse.version` (output).
- [ ] `UpdateEmployeeRequest.version`, `PatchEmployeeRequest.version`, `UpdateJurisdictionRequest.version` (input, required).

**Service — employee**
- [ ] create: set version = 1.
- [ ] PUT/PATCH: load existing → `existing.version != req.version` → 409; capture `expectedVersion`
      **before** bump; bump; pass `expectedVersion` to repo CAS.
- [ ] deactivate/reactivate: bump version (no token check; state-transition guard stays).

**Service — jurisdiction**
- [ ] create: version = 1.
- [ ] `PUT jurisdiction`: load → version check → 409; bump; CAS; do **not** touch employee.version.
- [ ] reconcile helper (replaces wipe-and-recreate): for id+version items check jurisdiction.version
      → 409 on mismatch, then update-by-id (bump); id-without-version → 400; insert id-less (v1);
      deactivate omitted; reject foreign id.

**Repository**
- [ ] employee `update`: CAS `… SET …, rowversion=? WHERE id=? AND tenant_id=? AND rowversion=?`
      → return rowsAffected>0; false ⇒ 409.
- [ ] jurisdiction `update`: same CAS on jurisdiction rowversion.
- [ ] reconcile queries: fetch existing active jurisdictions, update/insert/deactivate by id.
- [ ] **remove** employee-go `FetchForUpdate` (pessimistic) usage from the PUT path.

**Validation**
- [ ] require `version > 0` on the three update requests (mirror individual's validator).

**Tests**
- [ ] employee PUT/PATCH: stale version → 409; correct version → bump.
- [ ] jurisdiction PUT: stale version → 409; employee.version unchanged.
- [ ] reconcile: id+correct-version→update (bump), id+stale-version→409, id-without-version→400,
      no-id→insert (v1), omitted→deactivated, foreign id→reject.
- [ ] cross-level: B edits J1 (v2→v3); A's employee PUT carrying J1 v2 → 409 (no silent overwrite).
- [ ] employee create → versions = 1.

---

## 8. Rules a client follows (summary)
- Update the employee (any field, or replace its jurisdiction set) → send the **employee** version.
- Update one jurisdiction in place → send that **jurisdiction's** version.
- To keep/update an existing jurisdiction via employee PUT/PATCH → include its **`id` + `version`** in
  the array (both required; checked → 409 on mismatch). id-less items are inserted as new; omitted
  existing ones are deactivated. Easiest path: echo back the jurisdictions exactly as they came in the
  GET employee response, then edit the fields you want.