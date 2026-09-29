# Account/Tenant conformance — change log & run guide

A durable record of everything changed while bringing the Account service into conformance.
**A. Suite rebuild** (the shipped suite was based on old code) · **B. Spec fixes** · **C. Service
findings** · **D. Run recipe**.

## Context / targets

- **Contract / spec:** `v3.0.0/account.yaml` (mirrored into this dir as `schema.yaml`). Base path
  `/accounts/v3` (plural).
- **Two implementations of the same API:**
  - **Go** — `conformance/code/account-go`, deployed at context path **`/accounts`**
    → base URL `https://digit-lts.digit.org/accounts/v3`.
  - **Java** — `conformance/code/account`, deployed at context path **`/accounts-java`**
    → base URL `https://digit-lts.digit.org/accounts-java/v3`.
  - Both share one tenant database (same 14 tenants visible from either).
- **No Kong auth plugin** on this route (bootstrap service that mints tenants/realms) → the suite
  runs **tokenless**. Kong core still stamps `X-Kong-Request-Id` (+ latencies); no rate-limit headers.
- **Auth headers the service reads** (all optional, for audit): `X-Client-Id` (principal),
  `X-Tenant-Id` (a tenant **UUID**, required only to scope `/config`), `X-Request-Id`.
- Routes are hit **without trailing slashes** (Go gin redirects; Java Spring Boot 3 404s on a slash).

---

# Part A — Suite rebuilt from scratch

The shipped suite targeted an **older account API** (`/accounts`, `/signup`, `{"tenant":…}`
envelopes, `otpLength`/`documents` config) that no longer exists. The live deployment implements the
current `account.yaml` contract, so the suite was rewritten:
- `factories.py` → flat `TenantCreateRequest` / `TenantConfigCreateRequest{tenantId,configKey,configValue}` /
  `SignupVerifyRequest{referenceId,otp,purpose}`.
- `validators.py` → real shapes (`TenantResponse`, `{totalCount,…,tenants[]}`,
  `TenantConfigResponse`, `{…,configs[]}`, `SignupInitiateResponse{referenceId,expiresIn,cooldownSeconds}`,
  bare-`[Error]` bodies) + lenient error-body check that rejects wrapper envelopes.
- 3 behavioral files → paths `/tenants`, `/tenants/registrations{,/verify,/resend}`, `/config`;
  tokenless; **create→capture→hard-delete cleanup** via the `tenant_factory` fixture.
- `conftest.py` → hypothesis profiles (`bounded`/`full`); no-plugin Kong header profile
  (`X-Kong-Request-Id` required, rest optional); the `tenant_factory` cleanup fixture.
- `test_schema_conformance.py` → **reads-safe scope** (see Part D), phases `examples`+`fuzzing`,
  `schema.config.base_url`, standard check exclusions.

---

# Part B — Spec fixes (`v3.0.0/account.yaml` + suite `schema.yaml`)

1. **Uniform error envelope** — every error status now uses `type: array, items: $ref Error`
   (converted **44** non-400 responses that were a bare object). Previously 400 = array but all other
   errors = object; the array form matches `common.yaml`/all other specs and both implementations.
2. **`verifyRegistration`** — added **404** (Go, transitional) alongside the existing **422** (Java
   target) for unknown/expired referenceId.
3. **`resendRegistrationOTP`** — added **422** (Java target) alongside the existing 404.
4. **`createTenantConfig`** — added **422** (tenantId does not match an existing tenant; Java target).

Per-operation documented status codes after these edits:

| operation | codes |
|---|---|
| createTenant | 201,400,401,409,429,500 |
| listTenants | 200,400,401,429,500 |
| updateTenant | 200,400,401,404,409,429,500 |
| deleteTenant | 200,401,404,429,500 |
| initiateRegistration | 200,400,401,409,429,500 |
| verifyRegistration | 201,400,401,404,410,422,423,429,500 |
| resendRegistrationOTP | 200,400,401,404,410,422,423,429,500 |
| createTenantConfig | 201,400,401,409,422,429,500 |
| listTenantConfigs | 200,400,401,429,500 |
| updateTenantConfig | 200,400,401,404,409,429,500 |

`AuditDetail` and `Error` are left as `$ref`s to the shared `common.yaml` (already consistent with
other specs — not changed).

---

# Part C — Service findings

- **Java (all FIXED, verified live):** trailing-slash routing; status codes 409/404/429 (were 400);
  config wrong-type → 400 (was 201); 422 for verify/resend/config; audit principal default
  (`createdBy:"admin"` for new tenants instead of `""`).
- **Java (open, data only):** 6 legacy tenants still carry `createdBy:""` from before the audit fix →
  `GET /tenants` violates `AuditDetail.minLength:2`. One-time DB backfill needed (don't delete — `MAD1`
  etc. are in use).
- **Go (open):** config tenantId-mismatch → 400 (target 422, deploy pending). NUL-byte in a string
  filter → 500 (deferred non-human-input class; valid Unicode works; Java handles it as 400).

---

# Part D — Run recipe

```bash
cd conformance/services/account-service
source ../../.venv/bin/activate
GO=https://digit-lts.digit.org/accounts/v3
JAVA=https://digit-lts.digit.org/accounts-java/v3

# Behavioral (tokenless; self-cleaning — creates then hard-deletes every tenant)
pytest tests/test_response_contracts.py tests/test_error_contracts.py tests/test_stateful_flows.py \
  --base-url="$GO" --gateway=kong -v            # then repeat with --base-url="$JAVA"

# Schemathesis — reads-safe scope only (see below)
pytest tests/test_schema_conformance.py --base-url="$GO" --gateway=kong --hypothesis-profile=full
```

**Schemathesis scope (important):** only `GET /tenants`, `GET /config`, `POST …/verify`,
`POST …/resend` are fuzzed. The 6 mutating endpoints are skipped in `test_schema_conformance.py`
because fuzzing them could create a Keycloak realm, send OTP spam, or update/delete an existing
tenant/config (a fuzzed id can't be limited to our own records). Write **success** contracts are
covered by the behavioral suite, which cleans up after itself. Verified non-destructive: tenant count
is unchanged (`14 → 14`) across all fuzz runs.

Notes: run Go and Java **sequentially** (shared tenant DB). No token needed (no auth plugin). The OTP
`verify` success path is **not** testable end-to-end (OTP delivered out-of-band by email, no test
bypass in the service) — only its error paths are covered.
