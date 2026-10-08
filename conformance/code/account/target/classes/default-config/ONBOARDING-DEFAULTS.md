# Onboarding defaults: what must exist before a tenant can send anything

Three services collaborate to deliver an OTP or an account email, and each owns a different piece
of configuration. Two of those pieces fall back to a shared tenant and one does not, which is the
single most common reason a send fails on a new tenant.

This is the reference for what has to exist, where, and which parts are automatic.

- [Who owns what](#who-owns-what)
- [The fallback rules](#the-fallback-rules)
- [What a tenant needs](#what-a-tenant-needs)
- [The DLT rule for SMS](#the-dlt-rule-for-sms)
- [Purpose to template](#purpose-to-template)
- [Provisioning a new tenant](#provisioning-a-new-tenant)
- [Symptom to cause](#symptom-to-cause)
- [Known gaps](#known-gaps)

---

## Who owns what

| Service | Owns | Stored in |
|---|---|---|
| **notify** | the message: template text per channel, and which vendor sends it | `notification_config`, `provider_mapping` |
| **otp** | the OTP policy: length, TTL, cooldown, attempt limits | `otp_config` |
| **account** | nothing configurable beyond one template code it asks notify for | — |

Nothing here is shared between them. An OTP that never arrives can be missing a notify template, a
notify provider mapping, an otp config, or none of those — the message just failed at the vendor.

---

## The fallback rules

**This table is the part worth memorising.** The three lookups behave differently, and they fail in
ways that look similar.

| Lookup | Falls back to the platform tenant? | Where the fallback lives |
|---|---|---|
| notify **template** (`notification_config`) | **yes** | otp retries the whole HTTP call under the platform tenant |
| notify **provider mapping** (`provider_mapping`) | **no** | — |
| otp **config** (`otp_config`) | **no** | — |

The template fallback is in otp's client, not in notify:

```java
// otp/service/NotificationClient.java
String platformTenant = props.getNotifyPlatformTenant();          // NOTIFY_PLATFORM_TENANT, default "default"
if (isConfigNotFound(r.status, r.body) && !platformTenant.equals(tenantId)) {
    Resp fallback = postOnce(clientId, platformTenant, body);     // second call, platform tenant
```

Two consequences people get caught by:

- **notify templates only need to exist once**, under the platform tenant. Most tenants have none of
  their own and that is correct — they ride the fallback.
- **The moment you give a tenant its own template**, its first call succeeds, the fallback never
  fires, and notify then needs *that tenant's* provider mapping too. Adding a template without a
  mapping turns a working tenant into `No provider mapping found for channel EMAIL`.

The platform tenant is `default` here, configurable via `NOTIFY_PLATFORM_TENANT`. On the older
test-lts stack it is hardcoded to `global` — same mechanism, different name.

---

## What a tenant needs

### notify — `notification_config`, under the platform tenant

| Template code | Used by | Channels |
|---|---|---|
| `otp-login` | otp, `purpose=login` | SMS + EMAIL |
| `otp-transaction` | otp, `purpose=transaction` | SMS + EMAIL |
| `otp-password-reset` | otp, `purpose=forgot-password` | SMS + EMAIL |
| `otp-generic` | otp — `phone-verify` and `registration` share this one | SMS + EMAIL |
| `account-tenant-temp-password` | account, admin temp-password email | EMAIL |

`otp-mfa` is also seeded but nothing selects it: `mfa` is not an accepted purpose. Harmless, and
kept only because removing a template code from a live tenant is riskier than an unused row.

Seeded by `seed-defaults.sh` (beside this file), which also seeds the otp configs below.

### notify — `provider_mapping`, under the platform tenant

| Channel | Country | Providers |
|---|---|---|
| EMAIL | *(null — catch-all)* | `["gmail"]` |
| SMS | *(null — catch-all)* | `["smscountry"]` |

One catch-all per channel per tenant; a `country` value makes it specific to that country and takes
precedence. Every name must appear in `GET /v3/providers` or the mapping is rejected with 400.

### notify — the provider registry (automatic, do not seed)

`GET /v3/providers` lists the jars the pod loaded at startup. It lives in the **shared** schema, not
a tenant's — it describes the deployment, not the tenant. A provider whose credentials are unset
does not register at all, so a missing entry means missing env, not missing config.

Because the table is shared, `PATCH /v3/providers/{id}/status` disables a provider **for every
tenant on the cluster**.

### otp — `otp_config`, per tenant, per purpose

No fallback. Every tenant needs a row for every purpose it will use.

All five accepted purposes are seeded: `login`, `transaction`, `forgot-password`, `phone-verify`,
`registration`.

Seeded by `otp`'s `TenantConfigSeeder` when it consumes the tenant-provisioning event, and by
`seed-defaults.sh` for a tenant that never had one — the platform tenant, typically. The list is
derived from `Purposes.VALID` rather than written out, so a new purpose is seeded automatically —
it used to be a hardcoded pair, which is how `transaction` and `forgot-password` ended up accepted
by validation with a config on no tenant anywhere. Defaults
(length 6, TTL 300s, cooldown 120s, 3 attempts) come from `ConfigValidation.validateConfigFields`,
so create rows through `POST /otp/v3/config` — writing them any other way skips the defaults.

### Credentials

| Secret | Keys | Used by |
|---|---|---|
| `egov-notification-mail` | `mailsenderusername`, `mailsenderpassword` | notify gmail provider |
| `egov-notification-sms` | `username`, `password` | notify smscountry provider |

Env from a secret is injected **once at pod start**. Updating a secret does nothing until the pod is
restarted — `kubectl rollout restart deployment/notify -n egov`.

---

## The DLT rule for SMS

Indian operators enforce TRAI's DLT regime: the delivered text must match a template registered
against the sender, character for character.

**A mismatch produces no error anywhere.** The aggregator accepts the submission and returns
`200 OK:<jobid>`; notify records `DISPATCHED`; the operator drops it. The phone simply never rings.

The registered body for this account, as delivered by test-lts:

```
Dear Citizen, Your Login OTP is {{otp}}

EGOVS
```

47 characters with the placeholder, 45 rendered. The blank line and the trailing `EGOVS` entity
footer are part of the match. All five `otp-*` templates use this same body.

Rewording an SMS template is therefore not a cosmetic change. New SMS templates need the text
registered with the provider first.

Email has no equivalent constraint.

---

## Purpose to template

```
login            -> otp-login
transaction      -> otp-transaction
forgot-password  -> otp-password-reset
phone-verify     -> otp-generic
registration     -> otp-generic
```

Mapped in `otp/service/NotificationClient.templateFor(purpose)`, whose cases are the `Purposes`
constants so the switch stays visibly tied to `Purposes.VALID` — it previously mapped `mfa` and
`password_reset`, which validation rejects, so two templates existed that nothing could select.
One code per purpose; the channel is chosen by which address the caller supplies, not by the
template name. Supplying only a phone
sends SMS and skips EMAIL; supplying both sends both.

---

## Provisioning a new tenant

Account publishes `{"tenantId":"<CODE>"}` to `account-migration` when a tenant is created. From that
one event, automatically:

| | |
|---|---|
| every schema-separated service creates and migrates the tenant's schema | tenant-migration library |
| otp seeds `login` + `registration` configs | `TenantConfigSeeder` |

Nothing else is needed — notify templates and provider mappings are inherited from the platform
tenant via the fallback.

**Manual only when the event was missed or the tenant predates the service:**

```bash
curl -XPOST <service>/internal/migrate -H 'X-Tenant-ID: <CODE>'   # no body
# 200 done · 409 tenant migration disabled here · 400 unusable tenant id
```

Note account derives the tenant code from the name and **uppercases it** — a tenant named
`testnotify` becomes schema `TESTNOTIFY`. Lowercase tenants such as `default` cannot be created
through the account API; they have to be provisioned with `/internal/migrate`.

---

## Symptom to cause

| Symptom | Cause |
|---|---|
| `404 NotificationConfig not found with id: otp-login` | no template under the tenant **and** none under the platform tenant |
| `No provider mapping found for channel X` | the tenant has its own template but no mapping — see [fallback rules](#the-fallback-rules) |
| `No active providers registered for channel X` | mapping names a provider that did not load; check `GET /v3/providers` and the credential env |
| `SKIPPED — No phone number on recipient` | not an error: the caller supplied no address for that channel |
| `No configuration found for this tenant and purpose` | missing `otp_config` row — no fallback, seed it |
| `OTP generation is disabled for this purpose` | `otp_config.is_active = false` |
| `SMSCountry refused the message: No ACCESS Permission.` | wrong or placeholder SMS credentials, or pod not restarted after updating them |
| **SMS reports `DISPATCHED` but never arrives** | text does not match the registered DLT template |
| `500`, relation does not exist | tenant schema not created — run `/internal/migrate` |

---

## Known gaps

**Delivery reports are requested and discarded.** The smscountry provider sends `DR=Y`, but nothing
consumes the callback, so `DISPATCHED` means *accepted by the vendor* and never *delivered*. The
same is true of gmail (a later bounce is invisible) and of Twilio status callbacks. This is the
reason an SMS can report success and never arrive — see [the DLT rule](#the-dlt-rule-for-sms).

**`phone-verify` and `registration` share `otp-generic`**, so both send login-flavoured wording.
Fine for SMS, where the DLT-registered body is fixed anyway, but the email could read better with
templates of their own.

**Two purposes differ between the old and new stacks.** The older code mapped `mfa` and
`password_reset`; this one maps `forgot-password` and `phone-verify`, matching what validation
actually accepts. Anything integrating against both needs to know which it is talking to.
