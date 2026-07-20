# individual-go — Bug / Issue Tracker

A living list of suspected bugs and open questions found while reviewing the
service. Items here are **candidates** until discussed and confirmed — a "gut
says bug" may turn out to be by-design. We resolve each one deliberately.

**Status legend:** `OPEN` (found, not discussed) · `DISCUSSING` · `CONFIRMED`
(agreed real bug) · `REJECTED` (by-design / not a bug) · `FIXED`

**Severity:** `HIGH` (wrong result / crash / data loss) · `MED` (wrong behavior
in some paths) · `LOW` (cosmetic / fragile / perf) · `?` (depends on spec)

---

## Confirmed factual findings (behavior is unambiguous)

### B1 — `FindByIdentifier` queries non-existent `v1` tables
- **Status:** FIXED (in code, pending integration test) · **Severity:** was MED (latent)
- **Location:** `internal/repository/individual_repository.go` → `FindByIdentifier` (~L521–551)
- **Observation:** Uses raw SQL against `individual_identifier_v1` and
  `individual_v1`. The actual tables (per migrations + `TableName()`) are
  `_v3`. The function is currently **never called** anywhere, so it doesn't
  fire — but the moment it's wired to an endpoint it fails with
  `relation "individual_identifier_v1" does not exist`.
- **Resolution:** All `v1`→`v3` in the Go repo — the `FindByIdentifier` raw SQL
  (the real bug) plus the ~25 telemetry labels. Hardcoded literals, matching the
  billing precedent (no consts). Java was already correct. Go build clean.

### B2 — Wrong table names in telemetry labels (`v1`)
- **Status:** FIXED (via the B1 sweep) · **Severity:** was LOW (cosmetic)
- **Resolution:** the B1 change replaced all `v1`→`v3` (queries *and* telemetry
  labels, hardcoded literals). `grep -rn "_v1"` across `internal/` and `db/` is
  now empty. No separate work needed.
- **Location:** `internal/repository/individual_repository.go`, `config_repository.go`

---

## Candidate behavior bugs (need spec/intent to judge)

### B3 — Version check: validator lenient vs service strict (disagree)
- **Status:** FIXED (in code, pending integration test) · **Severity:** was HIGH
- **Decision:** `version` is REQUIRED on PUT — individual is an optimistic-concurrency
  API; safe-by-default and consistent with registry (which also requires it). Chose
  this over the optional / HTTP `If-Match` model (omit = bypass), because for a
  multi-operator person registry a silent clobber is a real hazard.
- **Resolution:** `ValidateUpdate` now rejects a missing/zero version up front with
  a clean `VALIDATION_ERROR` (400) "version is required for update", and the
  version-match check is strict (dropped the `> 0` guard) so validator and service
  agree. The authoritative concurrency guard remains the repo CAS (B9). Both
  services (Go `validator.go`; Java `RequestValidator.validateUpdate`). Verified:
  `go build ./...` and `mvn -o compile` clean.
- **Location:** validator `internal/validator/validator.go` → `ValidateUpdate`
  vs service `internal/service/individual_service.go` → `UpdateIndividual`

### B4 — PUT is additive, not a true "full replace"
- **Status:** FIXED (in code, pending integration test) · **Severity:** was ?
- **Location:** `internal/repository/individual_repository.go` → `Update` (~L120–202);
  service comment acknowledges it (~L197–204)
- **Observation:** `Update` creates/updates children (addresses, identifiers,
  documents) present in the request but **never deletes omitted ones**. If the
  spec's PUT means "replace the whole representation," an omitted child should
  be removed; today it cannot be removed via PUT.
- **Resolution:** True PUT full-replace — after upserting request children, any
  existing active child NOT in the request is deactivated (via the new `active`
  column shared with B5); `additionalDetails` absent => cleared. `version` is
  required (B3). Both services build clean.

### B5 — Soft-delete is partial (children asymmetry)
- **Status:** FIXED (in code, pending integration test) · **Severity:** was ?
- **Location:** `internal/repository/individual_repository.go` → `Delete` (~L216–246);
  enrichment `EnrichForDelete` (~L201–223)
- **Observation:** Delete deactivates the individual + identifiers, but **not**
  addresses or documents. May be intentional (addresses are shared many2many),
  but it's asymmetric.
- **Resolution:** Added an `active` column to the address + document tables (
- new
  migration, both services); `Delete` now deactivates documents + addresses (via
  the join) alongside identifiers, and reads return only active children. Both
  services build clean.

### B6 — `additionalAttributes` values forced to be strings
- **Status:** REJECTED (not a bug) · **Severity:** was ?
- **Verdict:** The contract mandates string values, so the validator is correct.
  `individual.yaml`: `additionalAttributes` is `type: object` with
  `additionalProperties: { type: string, maxLength: 1024 }`,
  `propertyNames: { pattern: '^[a-zA-Z0-9_.-]+$', maxLength: 128 }`,
  `maxProperties: 50`. `validateAdditionalAttributes` enforces exactly this.
  Relaxing it (allowing numbers/objects) would VIOLATE the contract.
- **Location:** `internal/validator/individual.go` → `validateAdditionalAttributes`

### B7 — `X-User-ID` mandatory on all routes, including reads
- **Status:** FIXED (in code, pending integration test) · **Severity:** was ?
- **Location:** `internal/middleware/headers.go` → `ExtractHeaders` (~L35)
- **Observation:** Missing `X-User-ID` → 400 on every API route, including
  GET search / get / exists. Reasonable for writes; possibly wrong for reads.
- **Resolution:** `X-User-ID` now required only for non-GET (mutations); GET reads
  no longer require it. Both services (Go `ExtractHeaders`, Java `HeaderValidationFilter`).

### B8 — `dateOfBirth` search compares string to timestamp column
- **Status:** REJECTED (not a bug) · **Severity:** was LOW
- **Verdict:** The column is `dateofbirth **date**` (migration
  `V20260520100000__individual_v3.sql`), NOT a timestamp. So
  `WHERE dateofbirth = '2006-01-02'` is a clean date = date comparison — no
  time-of-day / midnight fragility. The original flag wrongly inferred a
  timestamp from the Go `*time.Time` field; the actual column is `date`. Correct as-is.
- **Location:** `internal/repository/individual_repository.go` → `buildSearchQuery`

---

## Minor / lower-confidence notes (to verify)

- **N1 — N+1 queries in `Search`:** identifiers/documents loaded per-individual
  in a loop (`Search` ~L438–456). Correctness OK; perf concern at scale.
- **N2 — `DisallowUnknownFields` + body `tenantId`:** create/update decoders
  reject unknown fields; a client sending `tenantId` in the body (it belongs in
  the header) gets a 400. Matches spec intent but is a client-friction trap.
- **N3 — create forces `active=true`:** `EnrichForCreate` always sets
  `Active=true`, ignoring any `isActive` in the body. Likely intended.
- **N4 — PUT cannot change `active`:** `UpdateIndividual` sets
  `individual.Active = existing.Active` (~L158); soft-delete is the only
  deactivation path. Likely intended, noting for completeness.
- **N5 — unsalted SHA-256 mobile hash:** ~~`common.HashMobileNumber` is a plain
  unsalted SHA-256 (needed for deterministic search). Brute-forceable given the
  small mobile-number space; may be acceptable given it's an index, not a
  secret. Flag for security review.~~ **FIXED (both services build clean).** Now a
  keyed **HMAC-SHA256** blind index. The pepper (`HMAC_SECRET`) is held in
  config, never in the DB, so a hash-column leak can't be brute-forced back to
  plaintext. Required at startup **only when Vault is enabled** (fail-closed:
  Go `config.Validate`, Java `EncryptionService` ctor); empty pepper is tolerated
  Vault-off, where the mobile is plaintext at rest anyway so the pepper is
  security-irrelevant (no default injected, no startup warning). Both services key
  the HMAC identically (Java's empty-key `{0}` workaround zero-pads to the same
  64-byte block as Go's empty key → byte-identical hashes), so the shared-DB blind
  index stays consistent across services.

---

### B9 — Individual OCC is not atomic (lost-update window)
- **Status:** FIXED (in code, pending integration test) · **Severity:** was ? (MED if concurrent updates are real)
- **Decision:** individual is an optimistic-concurrency API (client sends `version`, stale writes
  rejected with 409). Fixed with a true optimistic **compare-and-swap**, NOT a pessimistic lock —
  chosen to stay coherent with the optimistic contract and to avoid holding a DB row lock across the
  Vault encryption call. (See concept C5.)
- **Resolution:** repo `Update` takes the client's `expectedVersion` and does a version-guarded write
  (`... WHERE id AND tenantid AND active AND rowversion = expected`); 0 rows affected ⇒ conflict,
  surfaced as `ROW_VERSION_MISMATCH` (409). No lock held during enrichment/Vault. Both services:
  - Go: `common.ErrOptimisticLock` sentinel; `Update(ctx, ind, expectedVersion)`; service uses
    `errors.Is` → `ErrRowVersionMismatch`.
  - Java: `IndividualRepository.update(ind, expectedVersion)` returns `false` on a 0-row conflict;
    service throws `ROW_VERSION_MISMATCH`.
  - Pre-update version check retained as a fast-fail. Verified: `go build ./...` clean, `mvn -o compile` clean.
- **Location:** service `internal/service/individual_service.go` → `UpdateIndividual`
  + repo `internal/repository/individual_repository.go` → `Update`; Java equivalents
- **Observation:** The version check is read-then-compare in Go, then `Save` by
  primary key only (`WHERE id = ?`, no `AND rowversion = ?`). Under Postgres
  default READ COMMITTED, two concurrent updaters can both read v1, both pass the
  Go check, and both write v2 — one silently clobbers the other. True OCC needs
  an atomic compare-and-swap: `UPDATE ... WHERE id = ? AND rowversion = ?` and a
  rows-affected check (or `SELECT ... FOR UPDATE`, or SERIALIZABLE).
- **Open question:** Do we need real concurrency safety here, or is the race
  acceptable for this workload? If needed, move the version guard into the SQL.

### B10 — Config `version` is a write-counter, not OCC (last-write-wins)
- **Status:** FIXED (in code, pending integration test) · **Severity:** was ?
- **Decision:** config stays in-place (approach B), `version` stays a monotonic
  counter with last-write-wins semantics (correct for an admin singleton — see
  concept C4). NOT full OCC. Fixed only the race that let the counter under-count
  / silently lose a write.
- **Resolution:** the upsert now reads the config row with a pessimistic row lock
  (`SELECT ... FOR UPDATE`) so concurrent upserts serialise, making the
  read-modify-write (version bump) atomic. Applied to BOTH services:
  - Go: `configRepository.GetByTenantForUpdate` (gorm `clause.Locking`), used by
    `Upsert`; relies on the tenantdb per-request transaction.
  - Java: `ConfigRepository.getByTenantForUpdate` (`... FOR UPDATE`); `upsert`
    marked `@Transactional` so the lock + update share the request tx.
  - Verified: `go build ./...` clean; `mvn -o compile` clean.
- **Location:** service `internal/service/config_service.go` → `Upsert`;
  Java `service/ConfigService.java` → `upsert`
- **Observation:** `Upsert` sets `body.Version = existing.Version + 1` and the
  client's submitted `version` is discarded (`ConfigToEntity` never copies it).
  There is no conflict detection at all — concurrent upserts are last-write-wins,
  and the counter can even under-count (both read v1 → both write v2). The
  version is purely informational.
- **Open question:** Is last-write-wins intended for config (likely fine — low
  write frequency), or should config enforce OCC like individual does? If the
  latter, the client `version` must be honored and checked.

---

### B11 — Baseline regex runs unconditionally; tenant `mobileRegex`/`nameRegex` can only tighten, never override
- **Status:** FIXED (in code, pending integration test) · **Severity:** HIGH (config feature did not work as documented)
- **Resolution:** Introduced `checkPattern` (validator/helpers.go) — per-field, a
  configured tenant regex **replaces** the baseline; baseline is used only when no
  tenant regex is set. Length/structural caps (`maxLen`, gender enum, lat/long,
  age) remain always-on safety floors. Config is now fetched once via
  `tenantConfig` and threaded into `validateFormats`.
- **Location:** `internal/validator/individual.go` → `validateFormats`
  (mobileBaseline ~L117; alphaOnly on givenName ~L89 & familyName ~L102) vs
  `applyTenantConfigValidations` (~L295–310)
- **Observation:** The in-file comments state the baseline is a fallback used
  "when no tenant-specific mobileRegex is configured" and that tenants can
  "override" it (and even "no platform pattern — tenant mobileRegex is the only
  enforcer"). But `validateFormats` applies the baseline **unconditionally and
  first**, then the tenant regex is applied on top. Net: the tenant regex can
  only make validation **stricter**, never override/loosen the baseline.
  - Repro: tenant sets `mobileRegex = ^\+91[0-9]{10}$`; input `+919876543210` is
    rejected by baseline `^[0-9]{6,15}$` before the tenant regex runs.
  - Same for names: baseline `alphaOnly` blocks `O'Brien` / `Anne-Marie` even if
    the tenant `nameRegex` allows them.
  - The two comments also contradict each other on whether a baseline exists.
- **Open question:** Intended contract? (a) baseline = hard floor + config tightens
  (then fix the comments), or (b) config overrides/replaces baseline when set
  (then make baseline conditional on "no tenant regex"). Pick one; today the code
  and its own docs disagree.

### B12 — Mobile uniqueness is hardcoded baseline → `uniquenessCriteria: ["mobileNumber"]` is redundant and cannot be disabled
- **Status:** FIXED (in code, pending integration test) · **Severity:** MED · was your "doing it twice" concern
- **Resolution:** Removed the hardcoded default mobile-uniqueness from
  `validateBusinessRules`. Uniqueness is now driven solely by
  `applyUniquenessCriteria` from the tenant config. **Default (no config) = no
  natural-key uniqueness.** The robust hash-first-then-plaintext lookup (needed
  for vault-encrypted tenants) is preserved in the new `mobileDuplicate` helper
  used by the `mobileNumber` criterion. ⚠️ Product-sensitive: this removes
  always-on mobile uniqueness — confirm no downstream consumer relied on it.
- **Location:** `internal/validator/individual.go` → `validateBusinessRules`
  default mobile-uniqueness (~L247–261) vs `applyTenantConfigValidations`
  `case "mobilenumber"` (~L317–336)
- **Observation:** Mobile uniqueness is enforced unconditionally in
  `validateBusinessRules` regardless of config. The config's `mobileNumber`
  uniqueness criterion then re-checks the same thing (a second DB round-trip,
  strictly redundant). Conversely, a tenant that omits `mobileNumber` from
  `uniquenessCriteria` (wanting to allow duplicate mobiles) still cannot disable
  it. So the config knob is misleading: it can't add anything (already on) and
  can't turn it off.
- **Open question:** Is mobile uniqueness a fixed platform rule (then drop the
  redundant config branch) or tenant-configurable (then the default check must
  be gated by config)?

### B13 — Tenant `nameRegex` applies to `givenName` only, not `familyName`
- **Status:** FIXED (in code, pending integration test) · **Severity:** LOW/MED
- **Resolution:** `nameRegex` now applies to both `givenName` and `familyName`
  (via `checkPattern` in `validateFormats`). `otherNames` left unconstrained
  (no baseline pattern there today) — revisit if the spec wants it covered.
- **Location:** `internal/validator/individual.go` → `applyTenantConfigValidations` (~L303)
- **Observation:** `nameRegex` is matched against `givenName` only. The baseline
  `alphaOnly` applies to both givenName and familyName, so familyName is subject
  to the platform baseline but never to the tenant regex — an asymmetry given the
  generically-named `nameRegex`.
- **Open question:** Should `nameRegex` also constrain `familyName` (and
  `otherNames`)?

---

### B14 — PUT child-matching semantics + identifier unique-index collision
- **Status:** FIXED (in code, pending integration test) · **Severity:** was MED
- **Location:** Go `internal/repository/individual_repository.go` → `Update`
  (child create/update loops + the B4 deactivation); Java `IndividualRepository.update`.
- **Observation:** Children are matched to existing rows by surrogate `id` only.
  Enrichment assigns a fresh id to any id-less child and `Save` upserts by id, so:
  id present ⇒ update in place; id absent ⇒ new row. But the contract's PUT
  example sends children WITHOUT ids ("send the desired set by value").
  - document / address: no natural key ⇒ id-based matching is the only option
    (no id ⇒ new row; omitted old rows are deactivated by B4 ⇒ replaced).
  - identifier: HAS a natural key `(individualId, identifierType)` (unique among
    active) but the code ignores it for matching. An identifier of an existing
    active type sent WITHOUT an id ⇒ INSERT ⇒ collides with
    `uk_individual_identifier_type_active_v3` ⇒ spurious 409. So the contract's
    own PUT example (AADHAAR, no id) fails on a second update.
  - The B4 deactivation runs AFTER the create/update loops, so the old identifier
    isn't freed before the new insert — contributes to the collision.
- **Resolution:** Service-level `reconcileChildren` (runs before enrichment,
  using the already-loaded `existing`): an id-less identifier adopts the id of the
  existing active identifier of the same type ⇒ in-place update, stable id, no
  collision. document/address stay id-based. Natural-key resolution removes the
  collision, so no deactivation reorder was needed. Both services build clean.

### B15 — Child writes not scoped to the parent individual (IDOR)
- **Status:** FIXED (in code, pending integration test) · **Severity:** was HIGH (cross-individual tampering within a tenant)
- **Location:** Go `internal/repository/individual_repository.go` → `Update` child
  loops (`Save`); Java `IndividualRepository.updateDocument/updateIdentifier/updateAddress`.
- **Observation:** Child updates match by child `id` alone, not by the owning individual:
  - identifier/document: `UPDATE ... SET individualid=<parent>, ... WHERE id=?` —
    a PUT for individual X sending a document/identifier id belonging to individual
    Y **reassigns Y's row to X** (theft).
  - address: `UPDATE ... WHERE id=?` (no individualid on the row) — X can
    **overwrite the content** of Y's address by supplying its id (can't steal it,
    the join is untouched, but can corrupt it).
  - Tenant `search_path` isolation blocks this across tenants, but NOT across
    individuals within the same tenant. Classic IDOR.
- **Resolution (two layers):**
  1. Service `reconcileChildren` rejects (400) any request child whose id is not
     among this individual's existing active children (checked in-memory against
     the freshly-loaded `existing`).
  2. **Repo-level (defense-in-depth):** every child update is scoped
     `WHERE id = ? AND individualid = ?` — Go: scoped update-else-create; Java:
     update-else-insert. So even a direct repo call can't match/reassign a row
     this individual doesn't own.
  Uniform across all three children (the join removal made address direct-FK too).
  Both services build clean.

### B16 — Java: a new child added on PUT was silently dropped (jdbc.update ≠ upsert)
- **Status:** FIXED (in code, pending integration test) · **Severity:** MED (Java only)
- **Location:** Java `IndividualRepository.update` child loops.
- **Observation:** `enrichForUpdate` assigns a fresh id to every id-less child, so
  the repo loop always took the `updateXxx` branch. Java `jdbc.update(... WHERE id=?)`
  does NOT upsert (unlike Go's GORM `Save`, which inserts on 0 rows), so a genuinely
  new child matched 0 rows and was **silently not inserted** — adding an
  address/identifier/document via PUT was lost on the Java side. (Go was unaffected.)
- **Resolution:** child loops now do update-else-insert (`if (updateXxx(x)==0) insertXxx(x)`),
  with the update scoped by `individualid` (same change as B15). New children
  insert; existing update in place; foreign ids can't be touched. Compiles clean.
- **Found via:** reviewing the B15 repo-level scoping — the id-only `WHERE` and the
  jdbc-vs-GORM upsert difference surfaced it.

### B17 — Go: missing migration to widen PII columns for Vault ciphertext (schema divergence)
- **Status:** FIXED (Java's widen migration copied verbatim into Go — byte-identical file, identical
  name `V20260625100000__widen_pii_columns_for_vault_ciphertext.sql`, so both compute the same Flyway
  checksum) · **Severity:** MED (Go; latent, triggers only when Vault enabled)
- **Location:** Go `db/migrations` — no equivalent of Java's `V20260625100000__widen_pii_columns_for_vault_ciphertext.sql`.
- **Observation:** the base create migrations declare `altcontactnumber varchar(20)` and
  `identifierid varchar(64)`. With Vault Transit encryption on, these fields are stored as
  ciphertext (`vault:v1:<base64>…`, ~60-70 chars even for short plaintext) and overflow the
  column, so encrypted writes fail. `mobilenumber` is already `varchar(256)` and is fine.
  Java carries a migration widening both columns to `varchar(256)`; Go does not, so the two
  services' schemas diverge and Go breaks identically once Vault is turned on.
- **Resolution (proposed):** mirror the widen migration into Go (`altcontactnumber` and
  `identifierid` → `varchar(256)`) so both services share one schema. NOT to remove it from
  Java — that would re-introduce the overflow the moment Vault is enabled. Awaiting sign-off.
- **Found via:** comparing the two services' migration sets while wiring the Java test run
  (Java had 8 migrations, Go 7; the extra one was the widen).

### B18 — Java: identifier created with active=false → invisible to every read (create-enrichment gap)
- **Status:** FIXED (integration-verified: GET returns the identifier; DB row active=t) · **Severity:** HIGH (Java only)
- **Location:** Java `EnrichmentService.enrichForCreate` child loops; surfaced via `IdentifierDTO.active`
  (primitive `boolean`, defaults `false`) → `ModelMappers` `e.setActive(d.isActive())` → `insertIdentifier`.
- **Observation:** a create request never sends `active`, so the DTO's `active` is `false`; the mapper
  copies it onto the entity, overriding the entity default `true`. `insertIdentifier` writes the `active`
  column explicitly (`i.isActive()`), so the row persists `active=false` and is filtered out by every
  read (`WHERE active=true`). Net effect: identifiers vanish immediately after create. Address and
  document map the same false, but their INSERTs omit the `active` column (DB default `true`), so the
  bug is masked there — only the identifier's explicit-`active` INSERT exposed it.
- **Resolution:** `enrichForCreate` now sets `active=true` on every request child (address, identifier,
  document), matching the create-lifecycle rule the update/delete paths already own. Server owns the
  active flag; DTO/mapper defaults no longer leak in. Removes the address/document latent flaw too.
- **Found via:** the first Java smoke create — GET returned address+documents but no identifiers; DB
  showed the identifier row present with `active=f`.

_Add new findings here as they surface — keep the ID sequence going (B19, B20, …
for behavior; N6, … for minor)._
