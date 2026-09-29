# How to add a new notification provider

You do not need to touch `notify-app` or `notify-spi`. Copy this template, fill in five things, build, and drop the jar.

A working reference implementation is in [notify-provider-examples/email/gmail/](../notify-provider-examples/email/gmail/).

---

## Step 1 — Copy this template

```bash
cp -r notify-provider-template notify-provider-twilio
```

---

## Step 2 — Rename in 4 places

| File | What to change |
|---|---|
| `pom.xml` | `artifactId` and `description` only — Nexus repo for `notify-spi` is already configured, do not add it again |
| `TemplateProvider.java` | Class name, package, `supportedChannel()`, `providerName()` |
| `TemplateProviderConfig.java` | Class name, package, env var names |
| `META-INF/services/org.digit.notify.spi.NotificationChannelProvider` | Fully-qualified class name of your provider |

**`supportedChannel()`** — return the channel your provider handles:

```java
Channel.EMAIL      // e.g. Gmail, Outlook, SendGrid
Channel.SMS        // e.g. Twilio, MSG91, Exotel
Channel.WHATSAPP   // e.g. Gupshup, Kaleyra
Channel.PUSH       // e.g. Firebase FCM
```

**`providerName()`** — a short unique string used in provider mappings and logs:

```java
return "twilio";   // must match what you configure in POST /v3/provider-mappings
```

---

## Step 3 — Implement `send()`

Receive a rendered message and recipient, call your provider's API or SDK, return success or failure. Never throw — always return a `DispatchResult`.

**What's available in `send()`:**

| Variable | What it is |
|---|---|
| `message.renderedBody()` | Message text, already filled with real values |
| `message.renderedSubject()` | Email subject (EMAIL only) |
| `message.renderedTitle()` | Notification title (PUSH only) |
| `recipient.email()` | Destination email address |
| `recipient.phone()` | Destination phone number |
| `recipient.countryCode()` | e.g. `"IN"`, `"US"` |
| `recipient.deviceTokens()` | FCM/APNs token list (PUSH only) |

**Pattern for every provider:**

```java
@Override
public DispatchResult send(ChannelMessage message, Recipient recipient, Map<String, Object> metadata) {
    // 1. guard: validate recipient has what you need
    if (recipient.phone() == null || recipient.phone().isBlank()) {
        return DispatchResult.failed(Channel.SMS, "twilio", "Recipient phone is missing");
    }
    try {
        // 2. call your SDK / HTTP API
        // TwilioClient.send(recipient.phone(), message.renderedBody());

        log.info("SMS sent to {} via twilio", recipient.phone());

        // 3. success
        return DispatchResult.dispatched(Channel.SMS, "twilio");
    } catch (Exception e) {
        log.warn("Twilio failed: {}", e.getMessage());

        // 4. failure — dispatch engine will try the next provider in the fallback chain
        return DispatchResult.failed(Channel.SMS, "twilio", e.getMessage());
    }
}
```

See [notify-provider-examples/email/gmail/](../notify-provider-examples/email/gmail/) for a complete working implementation.

---

## Step 4 — Read credentials from environment variables

```java
public class TwilioProviderConfig {

    private final String accountSid;
    private final String authToken;

    public TwilioProviderConfig() {
        this.accountSid = require("TWILIO_ACCOUNT_SID");
        this.authToken  = require("TWILIO_AUTH_TOKEN");
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException(key + " env var is required");
        return v;
    }

    public String getAccountSid() { return accountSid; }
    public String getAuthToken()  { return authToken; }
}
```

---

## Step 5 — Build and deploy

```bash
# build the fat jar (maven-shade bundles your SDK, excludes notify-spi)
mvn clean package

# verify the SPI registration file made it into the jar
jar tf target/notify-provider-twilio-1.0.0-SNAPSHOT.jar | grep services
# must print: META-INF/services/org.digit.notify.spi.NotificationChannelProvider

# drop into the providers folder and restart
cp target/notify-provider-twilio-1.0.0-SNAPSHOT.jar /path/to/notify/providers/
```

Start notify-app and look for this in the startup logs:

```
SMS : [twilio]    ← your provider loaded
```

If you see `SMS : []` — the jar was not found or the `META-INF/services` file is missing or has the wrong class name.

---

## Step 6 — Configure via API

### Create a notification template

```bash
curl -X POST http://localhost:8080/v3/notification-configs \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: default" \
  -d '{
    "templateCode": "otp-sms",
    "channels": {
      "sms": {
        "enabled": true,
        "body": { "en": "Your OTP is {{otp}}. Valid for 10 minutes." },
        "payloadBindings": { "otp": "$.otp" }
      }
    }
  }'
```

### Create a provider mapping

```bash
# first provider is primary, rest are fallbacks tried in order
curl -X POST http://localhost:8080/v3/provider-mappings \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: default" \
  -d '{ "channel": "SMS", "providers": ["twilio", "msg91"] }'
```

### Send a notification

```bash
curl -X POST http://localhost:8080/v3/notifications \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: default" \
  -d '{
    "templateCode": "otp-sms",
    "recipient": { "phone": "9876543210", "countryCode": "IN" },
    "payload":   { "otp": "847291" },
    "locale": "en"
  }'
```

---

## Troubleshooting

| What you see | Why | Fix |
|---|---|---|
| `SMS : []` in startup logs | Jar not found or `META-INF/services` missing/wrong | Check providers folder path and jar contents |
| `No provider mapping found` | Step 6 provider mapping not done | `POST /v3/provider-mappings` for your channel |
| `No template found for 'default' locale` | Missing `"locale"` in request | Add `"locale": "en"` to `POST /notify` body |
| `All providers exhausted` | Your `send()` returned `failed` | Check app logs for the actual SDK error |
| `Recipient phone is missing` | No `phone` in recipient | Add `"phone": "..."` to the `recipient` object |
