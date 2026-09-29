# notify

Sends notifications over SMS, Email, WhatsApp and Push.

You give notify a **template code** and a **recipient**. It looks up what that template
should say on each channel, renders the message, picks a provider for the channel, and sends
it — falling back to the next provider if one fails. Every attempt is recorded.

The vendors that actually deliver messages are not part of this service. They are separate
jars dropped into a directory and discovered at startup, so adding Twilio or a new SMS
gateway needs no change here.

```
  caller                     notify                        provider jar        vendor
  ──────                     ──────                        ────────────        ──────
  templateCode   ──▶  look up config for tenant
  recipient           render each enabled channel
  payload             pick providers for the channel  ──▶  send()         ──▶  Gmail SMTP
                      record every attempt                                     SMSCountry
```

---

## Contents

- [Three things you configure](#three-things-you-configure)
- [Quick start](#quick-start)
- [API reference](#api-reference)
- [The notification config in detail](#the-notification-config-in-detail)
- [Sending a notification](#sending-a-notification)
- [Sending over a topic instead of HTTP](#sending-over-a-topic-instead-of-http)
- [Providers](#providers)
- [Tenants and schemas](#tenants-and-schemas)
- [Configuration](#configuration)
- [When something does not send](#when-something-does-not-send)

---

## Three things you configure

Before notify can send anything for a tenant, three records have to exist. Missing any one
of them is the usual reason a send does nothing.

| # | What | Where it lives | Answers |
|---|---|---|---|
| 1 | **Notification config** | `/v3/notification-configs` | What does this message say, on which channels? |
| 2 | **Provider** | discovered from jars, listed at `/v3/providers` | Which vendors are available? |
| 3 | **Provider mapping** | `/v3/provider-mappings` | Which vendor should this tenant's EMAIL go through, and in what order? |

A provider is **not** configured through the API — it appears because its jar is present and
its credentials are set. The API only lists what loaded.

---

## Quick start

Five commands to a delivered email, locally.

**1. Start notify and its database**

```bash
cd src/services/notify
export GMAIL_FROM_ADDRESS='you@gmail.com'
export GMAIL_APP_PASSWORD='your-16-char-app-password'
docker compose up --build
```

Check the startup log — this line tells you which providers are usable:

```
Plugin loader summary: 3 jars scanned, 1 providers registered
  SMS     : []
  EMAIL   : [gmail]
  WHATSAPP: []
  PUSH    : []
```

`3 jars scanned, 1 registered` means two jars were found but could not start, almost always
because their credentials are not set. See [Providers](#providers).

**2. Create a notification config**

```bash
curl -X POST http://localhost:8080/v3/notification-configs \
  -H 'Content-Type: application/json' -H 'X-Tenant-ID: default' \
  -d '{
    "templateCode": "welcome-email",
    "tag": "onboarding",
    "channels": {
      "email": {
        "enabled": true,
        "subject": { "default": "Welcome, {{name}}" },
        "body":    { "default": "Hello {{name}}, your account is ready." },
        "payloadBindings": { "name": "$.user.firstName" }
      }
    }
  }'
```

**3. Map the EMAIL channel to a provider**

```bash
curl -X POST http://localhost:8080/v3/provider-mappings \
  -H 'Content-Type: application/json' -H 'X-Tenant-ID: default' \
  -d '{ "channel": "EMAIL", "providers": ["gmail"] }'
```

**4. Send**

```bash
curl -X POST http://localhost:8080/v3/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-ID: default' \
  -d '{
    "templateCode": "welcome-email",
    "recipient": { "email": "someone@example.com" },
    "payload": { "user": { "firstName": "Asha" } }
  }'
```

```json
{
  "notificationId": "ntf_01M2Q...",
  "templateCode": "welcome-email",
  "channels": [
    { "channel": "EMAIL",    "status": "DISPATCHED", "provider": "gmail", "reason": null },
    { "channel": "SMS",      "status": "SKIPPED", "provider": "none", "reason": "Channel disabled or not configured" },
    { "channel": "WHATSAPP", "status": "SKIPPED", "provider": "none", "reason": "Channel disabled or not configured" },
    { "channel": "PUSH",     "status": "SKIPPED", "provider": "none", "reason": "Channel disabled or not configured" }
  ]
}
```

> **Read the body, not the status code.** A send always answers `202` if notify accepted the
> request. Whether a message went out is in `channels[].status`.

---

## API reference

Every endpoint takes **`X-Tenant-ID`** as a header. It is required; it is never read from the
body. `X-User-Id` is optional and recorded as the actor.

**Base path.** In a deployment the service runs under the context path `/notify`, so the full
path is `/notify/v3/...`. Running locally with docker compose there is no context path, so it
is just `/v3/...`. Every example below uses the local form.

### Send

| Method | Path | Returns |
|---|---|---|
| `POST` | `/v3/notifications` | `202` with the per-channel outcome |

### Notification configs — what a message says

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v3/notification-configs` | `201`. `409` if that `templateCode` already exists for the tenant |
| `GET` | `/v3/notification-configs` | Optional `?templateCode=`, `?tag=`, `?isActive=` — combinable |
| `GET` | `/v3/notification-configs/{id}` | `404` if it belongs to another tenant |
| `PUT` | `/v3/notification-configs/{id}` | Replaces `channels` and `tag`. `templateCode` is immutable and `isActive` is untouched |
| `DELETE` | `/v3/notification-configs/{id}` | `204` |

### Provider mappings — which vendor a channel uses

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v3/provider-mappings` | `400` if a named provider is not loaded |
| `GET` | `/v3/provider-mappings` | Optional `?channel=` |
| `PUT` | `/v3/provider-mappings/{id}` | |
| `DELETE` | `/v3/provider-mappings/{id}` | `204` |

### Providers — read-only view of what loaded

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v3/providers` | Optional `?channel=` and `?isActive=` |
| `PATCH` | `/v3/providers/{id}/status` | Body `{"isActive": false}` |

### Health and docs

| Path | |
|---|---|
| `/actuator/health` | liveness and readiness |

Actuator's base path moves between environments. Locally it is `/actuator/health`; a deployment
setting `MANAGEMENT_ENDPOINTS_WEB_BASE_PATH=/` serves it at `/health`, which is what the chart's
probes point at.

### Status codes

| Code | When |
|---|---|
| `202` | send accepted — check `channels[]` for delivery |
| `201` / `204` | created / deleted |
| `400` | request failed validation, or a mapping named an unloaded provider |
| `404` | no such config for this tenant, or no such endpoint |
| `409` | that `templateCode` or mapping already exists |
| `422` | a template could not be rendered |
| `500` | unexpected failure |

Errors come back as a single object:

```json
{ "code": "NOT_FOUND", "message": "NotificationConfig not found with id: welcome-email",
  "timestamp": "2026-09-18T10:00:00Z", "traceId": "…" }
```

---

## The notification config in detail

One config holds the wording for **every** channel of one `templateCode`. There is one config
per `(tenantId, templateCode)`.

```json
{
  "templateCode": "otp-login",
  "channels": {
    "sms":      { "enabled": true,  "body": {...}, "payloadBindings": {...} },
    "email":    { "enabled": true,  "body": {...}, "subject": {...}, "payloadBindings": {...} },
    "whatsapp": { "enabled": false, "body": {...}, "payloadBindings": {...} },
    "push":     { "enabled": false, "body": {...}, "title": {...},   "payloadBindings": {...} }
  }
}
```

| Field | Channels | Meaning |
|---|---|---|
| `enabled` | all | `false` or absent → that channel is `SKIPPED` |
| — | all | a channel whose address is missing from `recipient` is also `SKIPPED`, so enabling a channel does not force every send to use it |
| `body` | all | locale → template text |
| `payloadBindings` | all | variable name → JSONPath into the request payload |
| `subject` | email | locale → subject template |
| `title` | push | locale → title template |

### How rendering works

Two steps, and both matter.

**Step 1 — bindings turn the payload into flat variables.** Each `payloadBindings` entry maps
a variable to a JSONPath expression evaluated against the request `payload`:

```
payload          { "otp": "123456", "expiry": { "minutes": 5 } }
payloadBindings  { "otp": "$.otp", "mins": "$.expiry.minutes" }
                 ↓
variables        otp=123456   mins=5
```

**Step 2 — the template is rendered with those variables.**

```
"Your code is {{otp}}, valid {{mins}} minutes."
  ↓
"Your code is 123456, valid 5 minutes."
```

### Two rules to remember

**Placeholders are flat.** Only `{{name}}` works — a single word. These do **not**:

```
{{user.name}}      nested access
{{#items}}…{{/items}}   sections and loops
{{{raw}}}          unescaped output
```

Anything nested is flattened by a binding instead. That is what JSONPath is for:

```json
"payloadBindings": { "firstName": "$.user.profile.firstName" }
```

**Every placeholder needs a binding, and every binding must resolve.** A placeholder with no
binding, or a JSONPath matching nothing, fails that channel outright rather than leaving a
gap in the message:

```json
{ "channel": "EMAIL", "status": "FAILED",
  "reason": "JSONPath expression '$.user.firstName' failed for channel EMAIL" }
```

This is deliberate — a message with a hole in it is worse than one that did not go out. But
it means a binding for an optional field will stop the whole message, so only bind fields the
caller always sends.

### Tagging and finding configs

`tag` is an optional free-form label for grouping — a feature, a team, a release. It plays no
part in sending; it exists so you can find related configs without knowing their codes.

```json
{ "templateCode": "otp-login", "tag": "auth", "channels": { ... } }
```

The list endpoint filters on it, and the filters **combine**:

```bash
# every auth template
curl 'http://localhost:8080/v3/notification-configs?tag=auth' -H 'X-Tenant-ID: default'

# only the ones currently enabled
curl 'http://localhost:8080/v3/notification-configs?tag=auth&isActive=true' -H 'X-Tenant-ID: default'
```

`tag` and `templateCode` match exactly; a filter you leave out is not applied. Results are
ordered by `templateCode`.

### What an update does, and does not, change

`PUT /v3/notification-configs/{id}` is a **full replace** of the content, not a patch:

- `channels` is overwritten wholesale — a `PUT` omitting the `sms` block **deletes** that
  channel's template rather than leaving it in place. Send the channels you want to keep.
- `templateCode` is **immutable**. It is still required in the body, and must match the
  config you are updating — send a different one and the request is rejected with `400`
  rather than quietly ignored. This is deliberate: callers address a config by its code and
  nothing references it by `id`, so a rename would break every sender still using the old
  code, with no error until the next send returns `404`. To move a template, create a config
  under the new code and delete the old one.
- `tag` is replaced, including being cleared when omitted.
- `isActive` is **not** touched. There is no way to enable or disable a config through the
  API today, so the flag only ever changes in the database.

### Locales

`body`, `subject` and `title` are maps keyed by locale, and `default` is the fallback:

```json
"body": {
  "default": "Your code is {{otp}}.",
  "hi":      "आपका कोड {{otp}} है।"
}
```

A request with `"locale": "hi"` uses the `hi` entry; anything else uses `default`. A config
with no `default` fails to render.

### A full two-channel example

```bash
curl -X POST http://localhost:8080/v3/notification-configs \
  -H 'Content-Type: application/json' -H 'X-Tenant-ID: default' \
  -d '{
    "templateCode": "otp-login",
    "channels": {
      "sms": {
        "enabled": true,
        "body": { "default": "Your code is {{otp}}. Do not share it." },
        "payloadBindings": { "otp": "$.otp" }
      },
      "email": {
        "enabled": true,
        "subject": { "default": "Your login code" },
        "body": { "default": "Hello {{name}},\n\nYour code is {{otp}}." },
        "payloadBindings": { "otp": "$.otp", "name": "$.user.firstName" }
      }
    }
  }'
```

Sending this with `{"otp":"123456","user":{"firstName":"Asha"}}` and a recipient carrying both
`phone` and `email` delivers two messages.

---

## Sending a notification

```
POST /v3/notifications
X-Tenant-ID: default
```

```json
{
  "templateCode": "otp-login",
  "recipient": {
    "phone": "+919876543210",
    "email": "asha@example.com",
    "countryCode": "IN",
    "deviceTokens": ["fcm-token-1"]
  },
  "payload": { "otp": "123456", "user": { "firstName": "Asha" } },
  "locale": "default",
  "metadata": { "requestId": "abc-123" }
}
```

| Field | Required | Notes |
|---|---|---|
| `templateCode` | yes | must match a config for this tenant |
| `recipient` | yes | every field inside is optional |
| `payload` | yes | the values bindings read from; `{}` if the template has no variables |
| `locale` | no | defaults to `default` |
| `metadata` | no | passed through to the provider untouched |

**You do not choose the channel.** The config decides. A template with SMS and EMAIL enabled
sends both; to send only email, use a template that only enables email.

**Give the recipient what the enabled channels need.** A template with SMS enabled and a
recipient with no `phone` returns `FAILED` for SMS with `"Recipient phone is missing"`. The
request is still accepted — the fields are all optional, so this surfaces per channel.

### Reading the response

```json
{ "notificationId": "ntf_01M2Q…", "templateCode": "otp-login", "channels": [ … ] }
```

You always get one entry per channel, with one of three statuses:

| Status | Meaning |
|---|---|
| `DISPATCHED` | the provider accepted it |
| `SKIPPED` | nothing was attempted — the channel is disabled or absent from the config, **or** the recipient carries no address for it |
| `FAILED` | see `reason` — render failure, no mapping, or the provider refused |

`notificationId` is a time-ordered ULID prefixed `ntf_`, and is the key into
`notification_log` and `notification_attempt` — where every attempt, including retries down
the provider chain, is stored.

---

## Sending over a topic instead of HTTP

The same send can arrive on a topic. It runs through the identical code path, so the result
is the same; the difference is you get no response back.

Enabled with `NOTIFY_TOPIC_SEND_ENABLED=true`. Kafka or Redis is chosen by `PUBSUB_TYPE`.

The message is the HTTP body, wrapped with the tenant — a topic message has no header to
carry it:

```json
{
  "tenantId": "default",
  "request": {
    "templateCode": "otp-login",
    "recipient": { "email": "asha@example.com" },
    "payload": { "otp": "123456", "user": { "firstName": "Asha" } }
  }
}
```

`request` is exactly the HTTP request body, unchanged.

The outcome is logged rather than returned:

```
Notify message dispatched: notificationId=ntf_01M2Q… templateCode=otp-login channels=EMAIL=DISPATCHED,SMS=SKIPPED
```

**Failure handling.** An unreadable message, one that fails validation, or an unknown
`templateCode` is retried with backoff and then dead-lettered. A send where a *channel*
failed is not retried — like the HTTP path it counts as handled, and the detail is in
`notification_attempt`.

Use the topic when you do not need the per-channel result and would rather not wait for the
send. Use HTTP when you need to know what happened.

---

## Providers

A provider is a jar that talks to one vendor over one channel. notify renders the message and
hands it over already finished — providers never see templates, payloads or the database.

Three shipped as examples, in `notify-provider-examples/`:

| Provider | Channel | Required environment |
|---|---|---|
| `gmail` | EMAIL | `GMAIL_FROM_ADDRESS`, `GMAIL_APP_PASSWORD`, optional `GMAIL_FROM_NAME` |
| `smscountry` | SMS | `SMSCOUNTRY_USERNAME`, `SMSCOUNTRY_PASSWORD`, `SMSCOUNTRY_API_URL`, optional `SMSCOUNTRY_SENDER_ID` |
| `twilio-whatsapp` | WHATSAPP | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_WHATSAPP_FROM` |

### How loading works

At startup notify scans `NOTIFY_PLUGINS_DIR` (default `/providers`), opens each jar in its own
classloader, and instantiates every provider the jar declares. Each provider reads its own
credentials in its constructor. **A provider whose credentials are missing does not register**
— the service still starts, that channel simply has no vendor. The startup summary is the
place to check, and a failure is logged with the missing variable named:

```
Failed to load jar: notify-provider-twilio-whatsapp-1.0.0-SNAPSHOT.jar
  Caused by: java.lang.IllegalStateException: TWILIO_ACCOUNT_SID env var is required
```

There is no hot reload. Adding a jar needs a restart.

### Writing one

The full walkthrough lives with the template it describes:
[`notify-provider-template/BUILDING_A_PROVIDER.md`](notify-provider-template/BUILDING_A_PROVIDER.md).
What follows is the shape of the contract.

**1. A project depending on `notify-spi` as `provided`**

```xml
<dependency>
  <groupId>org.digit.notify</groupId>
  <artifactId>notify-spi</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

`provided` matters: notify already has the contract, and a second copy in your jar makes Java
treat it as a different type, so your provider would not be recognised.

**2. Implement three methods**

```java
public class MyVendorSmsProvider implements NotificationChannelProvider {

    public MyVendorSmsProvider() {            // must be public and take no arguments
        this.apiKey = System.getenv("MYVENDOR_API_KEY");
    }

    @Override public Channel supportedChannel() { return Channel.SMS; }

    @Override public String providerName() { return "myvendor"; }   // the name used in mappings

    @Override
    public DispatchResult send(ChannelMessage message, Recipient recipient,
                              Map<String, Object> metadata) {
        if (recipient.phone() == null || recipient.phone().isBlank()) {
            return DispatchResult.failed(Channel.SMS, "myvendor", "Recipient phone is missing");
        }
        // message.renderedBody() is finished text — placeholders are already filled in
        // message.renderedSubject() / renderedTitle() are set for EMAIL / PUSH
        return DispatchResult.dispatched(Channel.SMS, "myvendor");
    }
}
```

Return `dispatched` and the chain stops. Return `failed` and notify tries the next provider in
the mapping. Throwing is caught and treated as a failure.

**3. Declare it** in `src/main/resources/META-INF/services/org.digit.notify.spi.NotificationChannelProvider`:

```
com.example.MyVendorSmsProvider
```

The file name is the interface; the contents are your class.

**4. Build a fat jar** with `maven-shade-plugin`, excluding the SPI and merging service files:

```xml
<transformers>
  <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
</transformers>
<artifactSet>
  <excludes><exclude>org.digit.notify:notify-spi</exclude></excludes>
</artifactSet>
```

Also pin `maven-compiler-plugin` — these projects have no parent to supply a version, and an
older default ignores `maven.compiler.release`.

**5. Deploy it**: put the jar where `NOTIFY_PLUGINS_DIR` points, set its credentials, restart,
and confirm it appears:

```bash
curl http://localhost:8080/v3/providers -H 'X-Tenant-ID: default'
```

**6. Map it**, or nothing will use it:

```bash
curl -X POST http://localhost:8080/v3/provider-mappings \
  -H 'Content-Type: application/json' -H 'X-Tenant-ID: default' \
  -d '{ "channel": "SMS", "providers": ["myvendor"] }'
```

`notify-provider-template/` is a working skeleton to copy.

### Fallback and per-country routing

`providers` is an ordered list — tried in order until one succeeds:

```json
{ "channel": "EMAIL", "providers": ["gmail"] }
```

Every name in the list must appear in `GET /v3/providers` or the mapping is rejected with `400`.
Only one EMAIL provider ships here, so the list has one entry — add a second name and it would be
tried whenever gmail fails.

An optional `country` routes by the recipient's `countryCode`, and a mapping without one is
the catch-all:

```json
{ "channel": "SMS", "country": "IN", "providers": ["smscountry"] }
{ "channel": "SMS",                  "providers": ["smscountry"] }
```

An Indian number matches the first mapping; every other country falls to the catch-all. Both name
`smscountry` because it is the only SMS provider that ships — with a second one loaded you would
point the catch-all at it instead.

---

## Tenants and schemas

> For the cross-service picture — which templates and configs account, otp and notify each need,
> which lookups fall back to the platform tenant and which do not — see
> [account/NOTIFICATION-SETUP.md](../account/src/main/resources/default-config/NOTIFICATION-SETUP.md).


Every record notify stores belongs to a tenant, and `X-Tenant-ID` is how you say which. That much
is true regardless of the setting below — what changes is where the rows physically live.

**Off (`TENANT_MIGRATION_ENABLED=false`, the default).** One set of tables, shared by every tenant.
Rows are kept apart by the `tenant_id` column, which every query filters on.

**On.** Each tenant gets its own Postgres schema, named after the tenant, holding its own copy of
the tables. A connection's `search_path` is set to the caller's schema as it leaves the pool, so a
query cannot reach another tenant's rows even if someone forgets the `tenant_id` filter. Schemas are
created automatically: account publishes the tenant on the `account-migration` topic, and notify
migrates a schema for it.

Two consequences worth knowing:

- **Notify needs a broker when this is on.** Schema creation is driven by that topic, so without
  `KAFKA_BROKERS` a new tenant silently never gets a schema, and its first API call fails with a
  missing-table error rather than anything that names the real cause.
- **Sends over the topic carry their own tenant.** There is no request for the filter to read, so
  `tenantId` in the message body is what gets bound — see
  [Sending over a topic](#sending-over-a-topic-instead-of-http).

**The provider registry is the one exception.** `GET /v3/providers` lists the provider jars this pod
loaded, which is a fact about the deployment and not about any tenant, so those rows stay in the
shared schema and the endpoint takes no `X-Tenant-ID`. Everything else — configs, mappings, logs,
attempts — is tenant data and routes per tenant.

---

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/notify` | database |
| `SPRING_DATASOURCE_USERNAME` | `notify` | |
| `SPRING_DATASOURCE_PASSWORD` | `notify` | |
| `SPRING_FLYWAY_ENABLED` | `true` | set `false` where migrations run separately |
| `NOTIFY_PLUGINS_DIR` | `/providers` | where provider jars are read from |
| `SERVER_SERVLET_CONTEXT_PATH` | none | set to `/notify` in a deployment |
| `NOTIFY_TOPIC_SEND_ENABLED` | `false` | enable the topic consumer |
| `NOTIFY_TOPIC_SEND` | `notify-send` | topic to consume |
| `KAFKA_CONSUMER_GROUP` | `notify-service` | consumer group |
| `PUBSUB_TYPE` | `kafka` | `kafka` or `redis` |
| `KAFKA_BROKERS` | `localhost:9092` | |
| `TENANT_MIGRATION_ENABLED` | `false` | per-tenant schemas — see [Tenants and schemas](#tenants-and-schemas) |
| `TENANT_MIGRATION_SCHEMA_TABLE` | `notify_schema` | Flyway history table; must match the init container's |
| `TENANT_MIGRATION_CONSUMER_GROUP` | `notify-service` | group for the tenant topic |

Provider credentials are listed under [Providers](#providers).

### Layout

| Directory | |
|---|---|
| `notify-app/` | the service |
| `notify-spi/` | the provider contract — the only thing a provider needs |
| `notify-provider-examples/` | three working providers |
| `notify-provider-template/` | skeleton to copy |
| `providers/` | built jars, mounted into the container |

`notify-app` is the only module with a database, which is why migrations live only there.

---

## When something does not send

Work down this list — it is ordered by how often each is the cause.

**`404 NOT_FOUND` — "NotificationConfig not found with id: X"**
No config for that `templateCode` **under that tenant**. Configs are per tenant; one created
under `default` is invisible to tenant `acme`.

```bash
curl 'http://localhost:8080/v3/notification-configs' -H 'X-Tenant-ID: default'
```

**`500` — "bad SQL grammar", or a message naming a relation that does not exist**
With [per-tenant schemas](#tenants-and-schemas) on, that tenant has no schema yet. Notify creates
one from the `account-migration` topic, so the usual cause is that it never saw the event — check
`KAFKA_BROKERS` is set and the startup log shows the subscription. `POST /internal/migrate` with
the tenant creates the schema directly.

**`400` — "Missing tenant"**
The request reached the tenant filter without `X-Tenant-ID`. Only `/v3/providers`, health and
`/internal/*` are exempt.

**`FAILED` — "No provider mapping found for channel EMAIL"**
The template rendered, but the tenant has no mapping for that channel. Create one.

**`FAILED` — "No active providers registered for channel EMAIL"**
A mapping exists, but none of the providers it names are loaded. Check `/v3/providers` and the
startup summary.

**`FAILED` — "JSONPath expression '$.x.y' failed for channel EMAIL"**
A binding pointed at something the payload does not contain. Fix the payload or the binding.

**`FAILED` — anything naming the vendor**, e.g. `535-5.7.8 Username and Password not accepted`
The provider was reached and the vendor refused. Credentials, sender identity or account
state. The full text is in `notification_attempt.reason`.

**`SKIPPED` — "Channel disabled or not configured"**
Expected for channels a template does not use. If it is a channel you wanted, set
`"enabled": true` on it.

**`SKIPPED` — "No phone number on recipient"** (or email address, or device token)
The channel is enabled, but the request carried no address for it. Not an error — it is how you
send on some of a template's channels and not others: supply only the addresses you want used.
Add the missing field to `recipient` if you did want that channel.

Which address each channel needs:

| Channel | `recipient` field |
|---|---|
| EMAIL | `email` |
| SMS | `phone` |
| WHATSAPP | `phone` — it addresses by phone number |
| PUSH | `deviceTokens` (non-empty) |

Blank counts as missing, so `"phone": ""` skips SMS rather than failing it.

**A provider is missing from the startup summary**
Its credentials are not set. The log names the variable.

**Everything looks right and nothing arrived**
Check the attempt trail:

```sql
SELECT a.channel, a.provider_name, a.attempt_no, a.status, a.reason
FROM notification_log l JOIN notification_attempt a USING (notification_id)
WHERE l.notification_id = 'ntf_…' ORDER BY a.attempted_at;
```

`provider_name = 'none'` means it never reached a provider — the failure was earlier, in
rendering or mapping.
