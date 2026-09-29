# Account Service (account)

A DIGIT 3.0 microservice that manages tenants, tenant configuration and
citizen tenant registration flows. It integrates with Keycloak for realm/user
setup, OTP for verification, notification for communication and pubsub for
tenant lifecycle events.

> **Setting up a new environment or debugging a message that never arrived?**
> [ONBOARDING-DEFAULTS.md](src/main/resources/default-config/ONBOARDING-DEFAULTS.md) is the reference for every template and config
> account, otp and notify need — which ones fall back to a shared tenant and which do not, the DLT
> rule that makes an SMS report success and never arrive, and a symptom-to-cause table.
> Seeding is `src/main/resources/default-config/seed-defaults.sh`.

## Overview

**Service Name:** account

**Purpose:** Provide tenant lifecycle management and onboarding flows for DIGIT
deployments, including tenant creation, tenant configuration and citizen
self-registration verification.

**Owner/Team:** DIGIT Platform Team

## Architecture

**Tech Stack:**
- Java 25
- Spring Boot 4
- PostgreSQL
- Keycloak Admin APIs
- `org.digit:tracer` platform library
- Kafka / Redis optional pubsub

**Core Responsibilities:**
- Create, search, update and delete tenants
- Create, search and update tenant configuration
- Register tenant users through OTP-backed flows
- Verify and resend tenant registration OTPs
- Provision and update Keycloak realm/client configuration
- Publish tenant lifecycle and tenant migration events
- Expose canonical APIs using request/response metadata envelopes

**Dependencies:**
- PostgreSQL required
- Keycloak required for tenant realm/user operations
- OTP service required for registration verification flows
- Notification service optional for outbound registration messages
- Kafka or Redis optional for pubsub events

## Features

- Tenant CRUD APIs
- Tenant configuration APIs
- Citizen self-registration flow
- OTP verification and resend support
- Keycloak realm/client automation
- Tenant migration event publication for schema-separated services
- Canonical APIs using `requestMetadata`, `ResponseMetadata` and `data`
- Actuator health, metrics and Prometheus endpoints

## Configuration

Main runtime properties are configured in
`src/main/resources/application.properties`.

| Property | Default | Description |
|---|---|---|
| `server.servlet.context-path` | `${CONTEXT_PATH:/account-java}` | Service context path |
| `server.port` | `${SERVER_PORT:8094}` | HTTP port |
| `account.server.canonical-api-prefix` | `${CANONICAL_API_PREFIX:canonical}` | Canonical route prefix |
| `account.keycloak.base-url` | `${KEYCLOAK_BASE_URL:http://keycloak:8080/keycloak}` | Keycloak base URL |
| `account.keycloak.admin-user` | `${KEYCLOAK_ADMIN_USER:admin}` | Keycloak admin username |
| `account.otp.base-url` | `${OTP_BASE_URL:http://localhost:8107/}` | OTP service base URL |
| `account.notification.base-url` | `${NOTIFICATION_BASE_URL:http://localhost:8091/}` | Notification service base URL |
| `account.pubsub.enabled` | `${PUBSUB_ENABLED:true}` | Event publishing toggle |
| `account.pubsub.topics.migration-topic` | `${PUBSUB_TOPIC_MIGRATION:account-migration}` | Tenant migration topic |

## API Reference

**Base path:** `/account/v3`

3.0 routes use request metadata in headers:

| Header | Required on | Description |
|---|---|---|
| `X-Tenant-ID` | Tenant-scoped endpoints | Tenant identifier |
| `X-User-ID` | Write endpoints | Audit user |
| `X-Request-ID` | Optional | Request/audit correlation |

### Tenant APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/tenants` | Create tenant |
| `GET` | `/v3/tenants` | Search tenants |
| `PUT` | `/v3/tenants/{id}` | Update tenant |
| `DELETE` | `/v3/tenants/{id}` | Delete tenant |

### Tenant Registration APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/tenants/registrations` | Start tenant registration |
| `POST` | `/v3/tenants/registrations/verify` | Verify tenant registration OTP |
| `POST` | `/v3/tenants/registrations/resend` | Resend tenant registration OTP |

### Tenant Config APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/config` | Create tenant config |
| `GET` | `/v3/config` | Search tenant config |
| `PUT` | `/v3/config/{id}` | Update tenant config |

## Canonical API

Canonical account APIs are available under:

`/account/v3/{CANONICAL_API_PREFIX}`

Default prefix: `canonical`

Canonical routes use `requestMetadata` instead of `X-*` request headers and
wrap business payloads under `data`.

```json
{
  "requestMetadata": {
    "tenantId": "ESMAGICO",
    "ts": 1712830200000,
    "requestId": "req-123",
    "userInfo": { "uuid": "user-1" }
  },
  "data": {
    "code": "ESMAGICO",
    "name": "ESMAGICO"
  }
}
```

## Local Run

```bash
mvn -pl src/services/account spring-boot:run
```

Set database and Keycloak environment variables as needed:

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=account
export DB_USER=postgres
export DB_PASSWORD=1234
export CONTEXT_PATH=/account
export KEYCLOAK_BASE_URL=http://keycloak:8080/keycloak
```
