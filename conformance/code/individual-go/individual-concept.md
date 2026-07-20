# individual-go — Concepts & Notes

A running notebook of concepts we discuss, commands used (with explanation +
examples), and code snippets. Reference material for later — each entry is
self-contained.

---

## C1 — Why does `GET /configs` have no "get by id", but `GET /individuals/{id}` does?

**Date:** 2026-07-07

### The question
The individual service exposes two resources. `GET /individuals/{id}` fetches
one individual by id, but `GET /configs` fetches the tenant's config using only
the `X-Tenant-ID` header — there's no `GET /configs/{id}`. Why the difference,
and what's the industry standard?

### Short answer
Config is a **singleton resource** (exactly one per tenant), so the tenant fully
identifies it — no id needed. Individual is a **collection resource** (many per
tenant), so you need an id to name a single member. This is the standard REST
"collection vs singleton" distinction. Having no `GET /configs/{id}` is correct
design, not a gap.

### First principle
> A URL path must carry *exactly enough* information to name one resource — no
> more, no less.

The deciding factor is **cardinality within a tenant**:

| | Individual | Config |
|---|---|---|
| How many per tenant? | Many (collection) | Exactly one (singleton) |
| What identifies one? | tenant + `{id}` | tenant alone |
| URL | `GET /individuals/{id}` | `GET /configs` |

- For individuals, the tenant narrows to thousands of rows; you still need a
  discriminator (`{id}`) to point at one. `GET /individuals` = search the
  collection; `GET /individuals/{id}` = one member.
- For config, once the tenant is known you are *already* pointing at the single
  row. Adding `/{id}` would be redundant — it can't select among multiple, and
  the client doesn't even know the id (never exposed).

### Why not expose the id and allow get-by-id anyway?
1. **One resource, one canonical URL.** Two URLs for the same row = more surface
   to secure/test/cache for zero benefit.
2. **The autoincrement id is a persistence detail, not domain identity.** Leaking
   a surrogate key couples clients to the DB and invites cross-tenant misuse. The
   config's *domain identity* is the tenant.

### Grounding in this codebase

Uniqueness — one row per tenant (`db/migrations/...__individual_config_v3.sql`):
```sql
CONSTRAINT uk_individual_config_tenant_v3 UNIQUE (tenantid)
```

The wire DTO does not expose `id` (`internal/models/config_dto.go`):
```go
type ConfigDTO struct {
    MobileRegex        string   `json:"mobileRegex,omitempty"`
    NameRegex          string   `json:"nameRegex,omitempty"`
    UniquenessCriteria []string `json:"uniquenessCriteria,omitempty"`
    Version            int      `json:"version,omitempty"`
    RequestID          string   `json:"requestId,omitempty"`
    AuditDetail        *AuditDetail `json:"auditDetail,omitempty"`
}
```

Routes — config has no `/:id`; individuals does (`internal/routes/routes.go`):
```go
individuals.GET("/:id", individualHandler.GetIndividual)   // collection member
configs.GET("",        configHandler.Get)                  // singleton, tenant-scoped
```

Lookup is keyed on tenant, not id (`internal/repository/config_repository.go`):
```go
db.WithContext(ctx).Where("tenantid = ?", tenantID).First(&cfg)
```

### Industry standard — "singleton scoped to a parent"
- `GET /user` / `GET /me` (GitHub, Stripe): the current user; identity from the
  token, no path id.
- `GET /repos/{owner}/{repo}/pages` (GitHub Pages config): one per repo,
  addressed by the parent, no id.
- AWS `GetBucketPolicy`: one policy per bucket, addressed by the bucket.
- Kubernetes cluster-scoped singletons: addressed by scope, not a generated id.

The fully-explicit form here would be `GET /tenants/{tenantId}/config` (parent
collection → singleton child). DIGIT carries tenant in a header as its
platform-wide multi-tenancy convention, so it collapses to `GET /configs` with
tenant implied — same pattern, tenant moved from path to header.

### When would config gain a get-by-id?
The day the product allows **multiple configs per tenant** (versioned or
per-department configs). Then it becomes a collection and you'd add
`GET /configs/{id}` or `/configs/{name}`. The `UNIQUE(tenantid)` constraint is
the signal that today it is deliberately a singleton.

### Related nuance (design, not a bug)
This service uses `POST /configs` as an upsert (201 create / 200 replace). For a
singleton, the more idiomatic verb is `PUT /configs` (idempotent replace of the
one config). `POST`-as-upsert is defensible but slightly non-canonical — revisit
if strict REST semantics matter.

---

## C2 — "version" is three different concepts; what config vs individual actually use

**Date:** 2026-07-13

### The question
Config has a version. In "optimistic concurrency" we usually create a *new*
record per version — but config has `UNIQUE(tenantid)`, so we can't create a new
row per version; we must update the existing row in place. Isn't OCC different
from an in-place version update?

### The untangling — three concepts that all say "version"
| Concept | Purpose | Shape | "version" is… |
|---|---|---|---|
| **OCC** (optimistic concurrency control) | Detect conflicting concurrent writes (lost-update) | **In-place** UPDATE guarded by old version | a **conflict token** (compare-and-swap) |
| **Versioned/temporal history** (SCD-2, event sourcing) | Keep history / time-travel | **New row per version**, old rows kept | a **history key** (part of identity) |
| **Monotonic write counter** | Audit "written N times" | In-place increment, no guard | just a **counter** |

**Key correction:** OCC is *classically an in-place update* — the in-place
version bump **is** OCC, not its opposite. The "new row per version" pattern is
**versioned history**, a different feature that merely reuses the word "version".
So "OCC is different from in-place update" is inverted: OCC *is* the in-place
guarded update; append-a-row is history.

### What config actually does (verified)
`internal/service/config_service.go` → `Upsert`:
```go
body.Version = existing.Version + 1   // read current, set +1
s.repo.Update(ctx, body)              // → GORM Save(cfg): UPDATE WHERE id = ?
```
The client's submitted version is **discarded** — `ConfigToEntity` never maps it:
```go
func ConfigToEntity(d *ConfigDTO) *Config {
    e := &Config{ MobileRegex: d.MobileRegex, NameRegex: d.NameRegex }
    // ... UniquenessCriteria only; Version NOT copied ...
    return e
}
```
⇒ For config, `version` is the **write-counter** concept, and the write is
**last-write-wins** — no conflict is ever detected.

### What individual does (verified)
`internal/service/individual_service.go` → `UpdateIndividual`:
```go
if existing.RowVersion != individual.RowVersion {
    return common.ErrRowVersionMismatch   // real conflict detection
}
```
⇒ For individual, `version` is a real **OCC conflict token**.

Same word, two different jobs across the two resources.

### The deeper catch — neither uses an atomic compare-and-swap
True OCC needs the version check to be atomic with the write:
```sql
UPDATE ... SET version = 2 WHERE id = ? AND version = 1;  -- then check rows-affected
```
But both resources do read-then-check-in-Go, then `Save` by primary key only
(`WHERE id = ?`, no `AND version = ?`). Under Postgres default READ COMMITTED,
two concurrent updaters can both read v1, both pass the Go check, and both write
v2 → **lost update**. See bugs.md B9 (individual soft-OCC) and B10 (config
last-write-wins).

### (see C3 for the config-vs-baseline validation decision & implementation)

### Why config can't keep history (the unique-tenant point)
`UNIQUE(tenantid)` forbids multiple rows per tenant, so versioned *history* is
impossible in this table by construction — the design is committed to
keep-latest-only (in-place). To add history you'd need a separate
`*_config_history` table (append-only) or a composite `(tenantid, version)` key
without the single-row uniqueness. For a config resource, keep-latest-only is
the normal, correct choice.

---

## C3 — Decision: tenant config overrides baseline (per-field); uniqueness is config-driven

**Date:** 2026-07-13 · **Resolves:** bugs.md B11, B12, B13

### The decisions
1. **Config regex overrides the baseline, per-field.** A configured tenant
   `mobileRegex` / `nameRegex` *replaces* the platform baseline for that field —
   it does not stack on top. Baseline applies only when the tenant hasn't set a
   regex for that field. Rationale: "specific beats generic" — a deliberate
   tenant rule outranks a platform default; otherwise the config API is
   pointless.
2. **Length / structural caps stay always-on.** `maxLen`, gender enum, lat/long
   ranges, age 0–150 are *system safety floors* (protect DB columns, prevent
   abuse), not business format — a tenant may not loosen them. Only the *format
   pattern* is tenant-overridable.
3. **No default natural-key uniqueness.** Removed the hardcoded mobile-uniqueness.
   Uniqueness is enforced only for fields a tenant opts into via
   `uniquenessCriteria`. Rationale: mobile is a *weak* identity field for a
   citizen/beneficiary registry (shared phones, recycled numbers) — forcing it
   by default causes real enrollment failures. `id` / `individualId` remain
   unique via their own keys.
4. **`nameRegex` covers `givenName` and `familyName`** (previously givenName only).

### Implementation (validator package)
`checkPattern` — the override rule in one place (`helpers.go`):
```go
func checkPattern(field, value, tenantRegex string, baseline *regexp.Regexp, baselineMsg string) error {
    if value == "" { return nil }
    if tenantRegex != "" {
        if re, err := regexp.Compile(tenantRegex); err == nil {
            if !re.MatchString(value) { return err("does not match the configured pattern") }
            return nil            // tenant pattern REPLACES baseline
        }
        // invalid tenant regex -> fall back to baseline (don't silently accept)
    }
    if !baseline.MatchString(value) { return errBaseline }
    return nil
}
```

Config fetched once, threaded through (`validator.go` → `ValidateCreate`/`ValidateUpdate`):
```go
cfg := v.tenantConfig(ctx, individual.TenantID)   // nil when none set
if err := v.validateFormats(individual, cfg); err != nil { return err }
return v.validateBusinessRules(ctx, individual, cfg, isCreate)
```

`validateFormats` picks tenant-or-baseline per field:
```go
mobileRegex, nameRegex := "", ""
if cfg != nil { mobileRegex, nameRegex = cfg.MobileRegex, cfg.NameRegex }
// givenName / familyName -> checkPattern(..., nameRegex, alphaOnly, ...)
// mobileNumber           -> checkPattern(..., mobileRegex, mobileBaseline, ...)
// maxLen caps applied unconditionally (safety floor) regardless of pattern source
```

Uniqueness is config-only now (`individual.go`):
```go
func (v *individualValidator) applyUniquenessCriteria(ctx, individual, cfg, isCreate) error {
    if cfg == nil { return nil }                    // no config -> no natural-key uniqueness
    // for each field in cfg.UniquenessCriteria: "mobilenumber" -> mobileDuplicate(); "name" -> FindByName()
}
```
`mobileDuplicate` keeps the hash-first, plaintext-fallback lookup so uniqueness
still works for vault-encrypted tenants.

### Status
Code compiles (`go build ./...` clean). **Behavioral verification still pending**
— needs the docker-compose stack + Postman config/individual scenarios to
confirm the override and opt-in-uniqueness paths end-to-end.

### Open follow-ups
- Product sign-off on removing default mobile uniqueness (B12) before release.
- Should `otherNames` also be covered by `nameRegex`? (left out for now)

## C4 — Versioning decision explained: history model vs concurrency (two separate axes)

**Date:** 2026-07-13 · **Decision:** config = in-place; individual = in-place (+ audit table only if history is a requirement); NOT new-row-per-version.

### The key to un-confusing this: there are TWO independent questions
People merge these, but they are orthogonal — you decide each separately:

- **Axis 1 — History model:** do we keep OLD versions, or only the CURRENT value?
  - (A) new row per update → old versions retained (time-travel possible)
  - (B) in-place update → only current value kept (old overwritten)
- **Axis 2 — Concurrency safety:** how do we stop two simultaneous writers from
  silently clobbering each other (the "lost update")? Options: optimistic
  compare-and-swap, pessimistic lock, or atomic increment.

"Atomic update avoids the race" is Axis 2. "New row vs in-place" is Axis 1.
You can have race safety in BOTH A and B. They are not alternatives to each
other — they answer different questions.

### Worked example — Axis 1 (A vs B)
Individual "Ravi", logical id `abc-123`. Created (v1), mobile changed (v2),
address changed (v3).

**(A) new row per version:**
| row_pk | id (logical) | version | mobile | is_latest | effective_from | effective_to |
|--------|--------------|---------|--------|-----------|----------------|--------------|
| r1 | abc-123 | 1 | 999 | false | Jan1 | Jan5 |
| r2 | abc-123 | 2 | 888 | false | Jan5 | Jan9 |
| r3 | abc-123 | 3 | 888 | true  | Jan9 | null |
- One logical person = 3 physical rows. "Current" = `is_latest=true`.
- Time-travel works: "Ravi on Jan6?" → r2.
- PK can't be `id` (3 rows share it) → surrogate PK + partial unique index
  ("one is_latest per id"). Child tables (address/identifier/document) must be
  versioned/snapshotted per person-version too.

**(B) in-place update:**
| id (PK) | version | mobile |
|---------|---------|--------|
| abc-123 | 3 | 888 |
- One row, ever. v1/v2 are gone. `version=3` just means "written 3 times."

### Worked example — Axis 2 (the race, and the fixes)
Two admins edit Ravi at once:
1. T1 reads Ravi (version=1)
2. T2 reads Ravi (version=1)
3. T1 writes mobile change → version=2
4. T2 writes email change → it still holds version=1 in memory, writes version=2,
   **overwriting T1's mobile** → T1's update silently LOST.

Three ways to prevent it:
- **Optimistic (compare-and-swap):** `UPDATE ... SET version=2 WHERE id=? AND version=1;`
  check rows-affected; 0 ⇒ someone beat you ⇒ reject (409), caller retries.
  → registry does this. Individual *compares in Go* but writes `WHERE id=?` only
  (no `AND version=?`) ⇒ race still open (bug B9).
- **Pessimistic (lock):** `SELECT ... FOR UPDATE` locks the row; the 2nd writer
  waits. → billing, otp do this.
- **Atomic increment:** `version = version + 1 RETURNING version` in one
  statement — no read-then-write gap for the counter. → otp_config does this.

### The decision, and WHY
**Config → (B) in-place, atomic counter.** Per-tenant singleton
(`UNIQUE(tenantid)`), identical to `otp_config`. Last-write-wins is acceptable
for an admin-set singleton; just make the bump atomic (fixes B10). No `id` to
add — Axis-1-A is simply the wrong question for a singleton.

**Individual → (B) in-place.** Rejecting (A) for FIVE concrete reasons:
1. **Requirement:** (A) only pays off if querying past versions is a routine
   product feature. Individual's product is "who is this person NOW."
2. **Child tables (decisive):** individual has address (many2many) + identifier
   + document children. (A) forces snapshotting ALL children per person-version.
   Registry's versioned unit is a single JSONB blob (no children) — cheap to
   version; individual is relational — expensive.
3. **Uniqueness:** `individualId UNIQUE` breaks under (A) (all versions share it);
   would need to drop it for a partial "one latest" index — schema surgery.
4. **Volume:** a person registry is large/frequently updated; (A) grows the main
   table unbounded and taxes every read/index.
5. **Simplicity:** (B) = one row, overwrite. (A) = surrogate PK + latest/effective
   columns + partial unique index + child-versioning + resolve-latest on reads.

**Why reject (A) even though registry (closest sibling) uses it:** "closest by
shape" ≠ "same requirement." Registry versions because its *product* is
versioned definitions queried by version, and its unit is a schemaless blob.
Individual's product is current-state and its data is relational with children.
Same platform, different requirement + data shape ⇒ different correct answer.
Copying registry's mechanism without registry's requirement is cargo-culting.

**If history IS required for individual:** still reject (A); use billing's
pattern — keep the live table in-place (lean reads, keep `individualId` unique,
no child-versioning) and snapshot the pre-update row into an append-only
`individual_audit` table in the same transaction. Full history, none of (A)'s
hot-path cost. NOTE: cannot lean on the Kafka events for this — they are
published but unconsumed (see memory: individual-events-unconsumed).

### Why each service chose what it did
| Service | Choice | Deciding factor |
|---|---|---|
| otp record | B, no history | ephemeral; only "valid now" matters; TTL reaps it |
| otp config | B, atomic counter | singleton per (tenant,purpose); last-write-wins ok |
| workflow | A (append) | the transition timeline IS the product |
| registry | A (versioned) | product = "give me version N"; unit is JSONB blob (cheap to version) |
| billing | B + audit table | hard "one live row" constraints force single row; history → side table |
| individual (proposed) | B (+ audit if history needed) | current-state product; child tables make A costly; wants conflict detection |
| config (proposed) | B, atomic counter | per-tenant singleton, like otp_config |

### Still open (product question, not an engineering one)
Is retaining a person's change history a real requirement?
- No → in-place, no history; just fix the B9 race (FOR UPDATE or version-in-WHERE).
- Yes → add billing-style `individual_audit` companion table.

---

## C5 — Concurrency: the layers, the two gaps, and optimistic vs pessimistic

**Date:** 2026-07-14

### `clause.Locking` / "transaction" / `SELECT FOR UPDATE` are NOT alternatives — they stack
1. **SQL primitive:** `SELECT ... FOR UPDATE` — the DB row lock.
2. **How you write it:** Go/GORM `.Clauses(clause.Locking{Strength:"UPDATE"})` *generates* that SQL;
   Java/JdbcTemplate = literal `... FOR UPDATE`; JPA = `@Lock(PESSIMISTIC_WRITE)`. Same thing.
3. **Transaction:** the scope that HOLDS the lock until commit. Required — with no surrounding tx,
   `FOR UPDATE` grabs then instantly releases the lock (useless).

A **transaction alone does NOT fix a lost update**: under READ COMMITTED two txns can both read
version=1 and both write version=2 (one write lost). You additionally need `FOR UPDATE`, or a
version-guarded WHERE (CAS), or SERIALIZABLE.

### Two gaps — and BOTH APIs have BOTH
- **Gap A — the server building & writing the row inside ONE request** (`read → assemble → write`),
  milliseconds. e.g. `body.Version = existing.Version + 1; repo.Update(...)`. No human involved.
- **Gap B — a human reading via GET, editing in a UI for seconds/minutes, then submitting**, across
  requests, on the client. No server code and no lock exists during it.

"Server building the row" (Gap A code) is NOT "human editing" (Gap B). `config.Upsert` and
`individual.Update` both do Gap-A building; both are fronted by Gap-B human editing. So that is NOT
what distinguishes them.

Timeline (config save):
```
09:55       GET /configs -> {version: 5}
09:55–09:57 human edits the form            <- Gap B (client, NO server code, NO lock)
09:57       POST /configs -> tx begins
   .000       GetByTenantForUpdate -> LOCK ACQUIRED
   .001       build row (version = 6)
   .002       UPDATE
   .003       COMMIT -> LOCK RELEASED         <- Gap A, ~3 ms
```
The lock covers only Gap A; it never spans the human's edit.

### The single deciding question
> Do we REJECT a write if the row changed since the client read it (i.e. protect Gap B)?
- **NO** → last-write-wins. Protect only Gap A (integrity) with `FOR UPDATE`. Body version ignored. → **config**
- **YES** → client sends the version it read; server rejects stale writes with 409. This is the
  optimistic *contract*. → **individual**

Mechanisms that close **Gap A** (needed either way):
- **Pessimistic** `FOR UPDATE` (lock the row for the request tx).
- **Optimistic CAS** (`UPDATE ... WHERE version = expected`; 0 rows affected ⇒ conflict).
- ⚠️ The version CHECK on an *unlocked* read does NOT close Gap A by itself — two same-version
  writers both pass the check, both write, one is lost. You still need a lock or CAS.

### Applied
- **config:** last-write-wins (no Gap-B rejection). Gap A closed with `FOR UPDATE` so the counter
  can't corrupt under two simultaneous POSTs. (B10 — done.)
- **individual:** rejects stale writes (Gap B) via the client `version` check = the optimistic
  contract, AND closes Gap A with an optimistic **compare-and-swap**: the repository update is guarded
  `... WHERE id=? AND tenantid=? AND active=true AND rowversion=<expected>`; **0 rows affected ⇒
  conflict ⇒ 409**. No lock is held during enrichment/Vault encryption. (B9 — done.)
  - Chose **pure optimistic CAS** over lock-then-verify (`SELECT FOR UPDATE`): coherent with the
    optimistic contract, never blocks, and (critically) avoids holding a DB row lock across the Vault
    encryption network round-trip. Matches registry precedent. The service keeps the version check as
    a fast-fail *before* doing enrichment/encryption work; the CAS is the authoritative guard.

## C6 — Address: many-to-many join removed → direct one-to-many

**Date:** 2026-07-14 · relates to B14/B15

Address↔individual was modeled many-to-many via `individual_address_join_v3`, but
nothing used the M:N: the contract describes an address as belonging to *an
individual*, the code never shared/de-duped an address, and there was no
reverse-lookup endpoint. Worst of both worlds — join-table complexity, zero
sharing benefit — and it made address the odd child (join subqueries in
delete/deactivation, the GORM `Omit("Addresses")` workaround, double writes).

**Removed it:** `individual_address_v3` now has an `individualid` FK; address is a
plain one-to-many child, loaded/written exactly like documents and identifiers.
Migration adds `individualid`, backfills from the join (1:1 in practice), drops
the join table. This also made the B14/B15 child fixes **uniform** across all
three children (no address special-casing).

Decision hinge: no shared/household-address roadmap ⇒ remove. If household
addresses ever become a feature, reintroduce M:N **with** dedup + a reverse-lookup
query — don't half-implement it again.

## Mirrored to Java `individual` service (2026-07-13)
Same three fixes applied to `service/RequestValidator.java` — the Java validator
was a structural twin with the identical issues:
- `checkPattern(...)` helper added; per-field tenant regex overrides baseline
  (uses RE2/J `.find()`, matching the Go partial-match semantics).
- Config fetched once via `tenantConfig(...)` and threaded into
  `validateFormats` / `validateBusinessRules`.
- Hardcoded default mobile-uniqueness removed; `mobileDuplicate(...)` +
  `applyUniquenessCriteria(...)` make uniqueness config-driven only.
- `nameRegex` now covers `familyName` too.
- Cross-service "Mirrors Go…" comments stripped (self-contained per repo rule).
- Verified: `mvn -o compile` clean; `TenantRegexEngineTest` passes.

## C7 — Managing scattered hardcoded values: classify, don't blanket-configure
The validators had magic numbers (150, 512, 64/128/256, 16/20/50/8, ±90/±180) and
baseline regexes spread across files. First principle: **not every literal is the same
kind of value** — where it belongs is decided by *who owns it* and *how often it changes*:

- **Domain invariant / dev-owned, ~never changes** (lat/long bounds, enum sets, field
  length caps, baseline regex) → **named constant in code**. The anti-pattern isn't
  hardcoding, it's *unnamed, duplicated* literals. Fix = name once, one location.
- **Per-environment** (DB host, port, URL) → env var (12-Factor). Already done here.
- **Per-tenant / business-owned, changes at runtime** (mobileRegex, nameRegex,
  uniquenessCriteria) → config service / DB. Already done via the config API.
- **Secret** → secret manager (Vault).

Industry mechanisms: 12-Factor config; "no-magic-number" linters (Checkstyle MagicNumber,
Go `mnd`); centralized dynamic config / feature flags (Configerator, Archaius, LaunchDarkly);
single-source schema/enums (protobuf / OpenAPI / DIGIT MDMS). Counter-lesson: **over-configuring
is its own failure** — a knob nobody turns adds untested surface, and bad config pushes are a
top cause of outages. So YAGNI: only externalize what genuinely varies.

**Tier-1 fix applied (this pass):** extract the scattered literals into ONE named-constants
location per service — Go `internal/validator/constants.go`, Java
`constants/ValidationConstants.java` — values unchanged, per-field names (e.g. `addrCountryMaxLen`
≠ `addrCityMaxLen` even though both existed) to avoid mislabeling. Regexes consolidated too.
Did NOT promote anything to runtime config (that would be Tier-3, only on real demand).

### ReDoS / why tenant regex is safe to accept (context for the regex constants)
A **DoS** exhausts a finite resource; the mechanism is usually *amplification* (tiny input →
huge work). **ReDoS** is that applied to a **backtracking** regex engine: a pattern like
`^(a+)+$` against `"aa…a!"` explores 2ⁿ partitions before failing — a ~40-char string pins a
CPU core. A tenant could set such a pattern as `nameRegex` (maliciously, or by accident with a
reasonable-looking `^([a-zA-Z]+)*$`) and every individual write would then run it on user input.
**We're immune because both services use RE2 / RE2J** — linear-time, no backtracking. Config-write
validation also compiles with the same engine, so "if it's stored, it's enforceable." **Rule to
preserve:** keep all tenant-regex handling on RE2/RE2J; swapping in `java.util.regex` anywhere
would reopen the ReDoS door. (Same family: billion-laughs/XML-expansion, zip bombs, HashDoS.)
