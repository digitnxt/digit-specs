# Account Service — Create Flows

Two ways an account (tenant) gets created: **admin create** (direct) and **self-service registration** (OTP-verified). Both end in the same provisioning.

Each endpoint also has a canonical twin under `/v3/canonical/...` — same logic, request context in the body envelope instead of headers.

---

## 1. `POST /v3/tenants` — admin create

Direct create. No OTP, no Redis.

| Step | Dependency | What happens |
|---|---|---|
| 1 | **Postgres** (`tenant_v1`) | tenant row inserted; the `UNIQUE (code)` constraint is what returns 409 on a duplicate |
| 2 | **Keycloak** | realm provisioned — see below |
| 3 | **Kafka/Redis pub-sub** | `account-create-tenant` + `account-migration` |

**Step 3 detail:** the `account-migration` event is how every other service learns a new tenant exists and creates its schema. If it fails to publish it is logged, not thrown — tenant already exists, so the request still succeeds, but that tenant has no schema anywhere until `POST /internal/migrate` is called on each service.

### What Keycloak provisioning does

1. `POST /realms/master/.../token` — admin token (admin-cli, password grant)
2. `POST /admin/realms` with `realm_config.json` — creates the whole realm in one shot:
   - **Clients:** `auth-server` (confidential, service account + authorization services on), `employee-iam-client` (service account only), `sandbox-ui-client` (public, browser redirect URIs) + Keycloak built-ins
   - **Realm roles:** `SUPERUSER`, `ADMIN`, `EMPLOYEE`, `CITIZEN`, `USER`, `RESOLVER`, `ASSIGNER`, `approver`, `field_inspector`, `counter_employee`, `document_verifier`, …
   - **Users:** the tenant email as realm admin with `SUPERUSER`, email pre-verified; plus the two service-account users
   - **Authorization (on `auth-server`):** ~179 resources = one per API path across all DIGIT services (account, billing, boundary, employee, filestore, idgen, individuals, localization, mdms, notification, otp, pg, registry, template, url, workflow), 5 scopes (`get/post/put/patch/delete`), ~310 scope permissions, all pointing at one role policy (`keycloak-demo`) listing the roles allowed to hit protected endpoints
3. `POST .../identity-provider/instances` — `citizen` OIDC IdP brokering to the shared `CITIZEN` realm
4. `PUT .../identity-provider/instances/citizen/management/permissions` — enable fine-grained permissions
5. `POST .../realm-management/authz/.../policy/client` — client policy `auth-server-token-exchange-policy`
6. `GET` + `PUT` the `token-exchange.permission.idp.*` permission — attach that policy so `auth-server` may exchange a CITIZEN token for a tenant-realm token

---

## 2. Self-service registration — 3 calls

### `POST /v3/tenants/registrations` (initiate)

1. **Postgres** — duplicate code check *before* spending an OTP → 409
2. **OTP service** — `POST otp/v3/generate` with `identifier = email:<email>`, `purpose = registration`, caller IP as metadata, header `X-Tenant-Id: <platform tenant>` (registration happens before the tenant exists). Returns `referenceId`, `expiresIn`, `cooldownSeconds`
3. **Redis** — `SET signup:<referenceId> <tenant payload> EX <expiresIn>`; the payload is parked here, nothing is written to Postgres or Keycloak yet

Response carries only `referenceId` / `expiresIn` / `cooldownSeconds`.

Error mapping: OTP 429 → `TOO_MANY_REQUESTS`, anything else → `OTP_SERVICE_ERROR` (503).

### `POST /v3/tenants/registrations/resend`

**Redis** get (404/422 if reference expired) → **OTP service** `otp/v3/resend`. Returns a fresh window. OTP 423 → locked, 429 → rate-limited.

### `POST /v3/tenants/registrations/verify`

1. **Redis** — fetch `signup:<referenceId>`; missing/expired → `REQUEST_EXPIRED` (422)
2. **OTP service** — `POST otp/v3/verify`. 423 → locked, 410 → expired, 422 → wrong OTP
3. **Same as admin create** — Postgres insert → Keycloak realm → events
4. **Redis** — delete the key (best-effort)

Returns 201 with the tenant.

---

## Dependency summary

| Dependency | Admin create | Registration |
|---|---|---|
| Postgres (`tenant_v1`) | yes | yes (dup check at initiate, insert at verify) |
| Keycloak | yes | yes (at verify only) |
| OTP service | — | yes (generate / resend / verify) |
| Redis | — | yes (parks payload between initiate and verify) |
| Kafka / pub-sub | create + migration events | same |

**One-line difference:** admin create trusts the caller and provisions immediately; registration proves the email first via OTP, holding the payload in Redis, then runs the identical provisioning.
