# Account/Tenant conformance — Java (`/accounts-java/v3`) vs Go (`/accounts/v3`)

Live runs through Kong, tenant data shared between both implementations.

**This service is special:** it *mints* tenants + Keycloak realms and is the bootstrap for the
whole platform, so its Kong route has **no auth plugin** (there is no pre-existing token/tenant to
enforce). The suite therefore runs **tokenless**. Because create/delete provision and tear down real
Keycloak realms, the behavioral suite is strictly non-destructive: every tenant it creates is tracked
and **hard-deleted at teardown** (delete cascades and removes the realm), and the fuzzer never
creates/updates/deletes existing data.

## Behavioral + contract suite (53 tests)

| Target | Result |
|---|---|
| **Java** (`/accounts-java/v3`) | **53 / 53 ✅** |
| **Go** (`/accounts/v3`) | 51 / 53 (1 transitional fail + 1 OTP-tolerant skip) |

Tenant count `14 → 14` after every run (0 leftovers) — the `tenant_factory` teardown works on both.

### Java — fully conformant (all fixes landed)
Fixed during this engagement and re-verified live:
1. **Route trailing-slash** — the documented no-slash paths route correctly.
2. **Status codes** — business errors now map correctly: duplicate → **409**, not-found → **404**,
   resend cooldown → **429** (previously all collapsed to 400).
3. **Config type validation** — `POST /config` with wrong JSON types now **400** (was coerced → 201).
4. **422** for `verify`/`resend` unknown referenceId and `config` tenantId-mismatch.
5. **Audit principal** — new tenants now get a real `createdBy`/`modifiedBy` (default `admin`) instead
   of `""`.

### Go — 1 transitional failure (pending Go deploy)
- `config tenantId-mismatch` still returns **400**; the suite/spec now expect **422** (Java's target,
  to be implemented in Go). This is the only behavioral gap and is expected until the Go deploy.
- (1 skip = `signup initiate` tolerating an OTP-downstream `429/503`.)

---

## Schemathesis property-based suite (reads-safe scope)

Fuzzing must never create/update/delete existing data on this realm-minting, shared service, and a
fuzzer-generated id can't be constrained to "our own records". So only **4 endpoints** are fuzzed —
the 2 reads plus `verify`/`resend` (which, with a fuzzed referenceId/otp, always bounce off with a
4xx and touch nothing but transient OTP sessions). The **6 mutating endpoints are skipped**
(`POST /tenants`, `PUT/DELETE /tenants/{id}`, `POST /tenants/registrations`, `POST /config`,
`PUT /config/{id}`). Proven safe: tenant count `14 → 14` across every fuzz run.

| Target | Result |
|---|---|
| Java | 3 pass / 1 fail (`GET /tenants` — legacy data) |
| Go   | 3 pass / 1 fail (`GET /tenants` — deferred NUL class) |

### Finding — Go: NUL byte in a string filter → 500  (DEFERRED, non-human input)
`GET /tenants?name=<…%00…>` → **500** `INTERNAL_ERROR`, leaking the Postgres error
`invalid byte sequence for encoding "UTF8": 0x00 (SQLSTATE 22021)`. Same NUL-in-string → 500 class
seen on employee/individual/accesscontrol. **Accepted / deferred** (no real client sends a NUL; valid
non-ASCII UTF-8 works). Java **catches** this DB error and returns **400** instead — more robust here.

### Finding — Java: empty audit fields on legacy tenants (data, not a live bug)
`GET /tenants` returns **6 pre-existing tenants** with `createdBy: ""` / `modifiedBy: ""` (created
*before* the audit fix), which violates the shared `common.yaml AuditDetail.minLength: 2`. The fix is
creation-side, so **new tenants conform** (verified: a fresh tenant gets `createdBy:"admin"`); only
the legacy rows carry `""`. **Resolution:** one-time DB backfill of those rows
(`SET createdby='system' WHERE createdby=''`) — do NOT delete them; several (e.g. `MAD1`) are in
active platform use.
> Root cause recap: Go's serializer omits the empty field (conforms); Java emitted `""` for the same
> DB value (violates). This was a **service** issue, not a spec one — the shared `AuditDetail` schema
> is correct and Go already satisfied it.

---

## Summary
- **Java: behaviorally conformant (53/53).** Remaining schema failure is stale legacy data, not a bug.
- **Go: conformant pending one deploy** (config-mismatch → 422) plus the deferred NUL-in-filter
  hardening.
- The account **spec was out of date** vs the deployment and the conformance suite was **rebuilt from
  scratch** (see changes.md); several spec inconsistencies were fixed (uniform error array, missing
  404/422 codes).
