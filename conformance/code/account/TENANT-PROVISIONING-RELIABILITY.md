# Tenant provisioning: partial failures and how to fix them

Companion to [KEYCLOAK-PROVISIONING.md](KEYCLOAK-PROVISIONING.md), which inventories *what* gets
created. This is about what happens when creating it only half works.

## The failure, as observed

uat-saas, 2026-09-25, tenant BOL3:

```
05:44:30  POST /accounts/v3/tenants        attempt 1
05:45:13  admin password credential written to Keycloak
05:45:40  400 (70011 ms)  "failed to create Keycloak realm: failed to list authentication flows"
05:45:40  POST /accounts/v3/tenants        attempt 2 (retry)
05:45:44  temp-password email sent          <-- a DIFFERENT password
05:45:45  201 (4985 ms)
```

The admin could not sign in. The password in the email was never written to Keycloak; the password
Keycloak held came from the failed attempt and was never sent to anyone.

BOL1, BOL2 and BOL4 were created in a single attempt on the same day and work normally.

## Why

Three things compound.

**1. Rollback is asymmetric.** `TenantService.create` provisions the DB row first, then the realm.
On a Keycloak failure it deletes the DB row — and nothing else:

```java
catch (RuntimeException kcErr) {
    tenantRepo.delete(entity.getId());     // only the DB row
    throw new RuntimeException("failed to create Keycloak realm: " + kcErr.getMessage());
}
```

`createRealmWithFullConfig` is ~6 sequential admin-API calls (realm, clients, roles, flows, user,
credential). Whatever landed before the failure stays in Keycloak, unreferenced.

**2. Deleting the DB row lets the retry run.** That row is the only thing that would have rejected a
second attempt as a duplicate code. Removing it means the retry proceeds against a dirty Keycloak.

**3. `409` is treated as success.**

```java
if (resp.statusCode() != 201 && resp.statusCode() != 409) { throw ... }
```

So the retry POSTs the realm, Keycloak answers `409 already exists`, the client accepts it and skips
ahead — finishing in 5s instead of ~50s. It then generates a fresh temporary password, emails it,
and never writes it, because the user already exists. **The failure is silent: the API returns 201.**

A realm scan on uat-saas found 14 realms with incomplete provisioning — 13 missing the `admin`,
`citizen` and `employee` clients, one (`TESTMAD24`) missing only `admin` — consistent with different
steps failing on different tenants.

## Contributing factor: it takes 47-70 seconds

Realm provisioning runs synchronously inside the HTTP request:

```
BOL1 201 (63972 ms)   BOL2 201 (57570 ms)   BOL4 201 (47292 ms)   BOL3 400 (70011 ms)
```

Everything else follows from this. Requests past 60s return `504` at the gateway even when they
succeed (COL40: `201 (35574 ms)` client-side 504). Kong's `account` service also has `retries = 5`,
and tenant creation is not idempotent, so a timed-out POST can be replayed into a second attempt.

Unmeasured: *why* six admin calls take a minute. Worth establishing before building around it.

## Three strategies, and only three

Two systems, no shared transaction, so: compensate, converge, or defer.

### A. Delete the realm on failure — symmetric rollback

Safe if scoped to realms *we* created, which the status code already tells us:

```java
boolean weCreatedTheRealm = (resp.statusCode() == 201);   // 409 means it pre-existed
...
catch (RuntimeException kcErr) {
    if (weCreatedTheRealm) keycloakClient.deleteRealm(code);
    tenantRepo.delete(entity.getId());
}
```

Without that guard it would delete realms it did not create.

**Cannot cover process death.** Compensation only runs in a `catch`; an OOM kill, restart or node
drain mid-provision leaves the same orphan. That is inherent to the strategy, not a gap in the code.
Rollback can also fail — Keycloak slow enough to time out a create is slow enough to time out a
delete.

### B. Idempotent reconcile — converge forward

Make each step safe to repeat, so a retry repairs instead of skipping: `409` on the realm means
"exists, now verify the rest"; GET-then-create each client, role and flow; and if the admin user
exists, **reset its credential to the password about to be emailed** rather than skipping it. That
last point is what would have fixed BOL3 on retry.

Stronger than rollback from first principles: the target state is fully known, so convergence works
regardless of how the current state arose — failed step, crashed pod, manual edit, older version. It
is also the only option that repairs the 14 realms already broken. Scope the password reset to users
still carrying `UPDATE_PASSWORD`, so a real admin's chosen password is never overwritten.

### C. Provision asynchronously

Return `202` with the tenant `PROVISIONING`; a worker provisions off the event and moves it to
`ACTIVE` or `FAILED`; the email goes out only on success. Fixes the root cause — the 47-70s request
— and with it the 504s and the Kong replay. Changes the API contract, and still needs B underneath,
because a background retry must also be idempotent.

## Order

| Step | Fixes | Size |
|---|---|---|
| Stop treating `409` as success | the silent skip; prerequisite for the rest | tiny |
| A — scoped realm delete | orphans from caught failures | small |
| B — idempotent reconcile | orphans from any cause incl. crashes; repairs the existing 14 | medium |
| C — async provisioning | the 47-70s request, 504s, Kong retries | large |

The first two together would have prevented BOL3. B is the one that covers process death and the
already-broken realms; C is the real fix for the timeout class.
