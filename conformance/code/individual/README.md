# Individual Service (individual)

A DIGIT 3.0 microservice that manages tenant-scoped individual/person records
and individual service configuration. It supports ID generation, secure
sensitive data handling, search/existence APIs, pubsub events and canonical
APIs.

## Overview

**Service Name:** individual

**Purpose:** Provide a reusable person/individual registry for DIGIT services,
including profile data, address/contact information and tenant-scoped
configuration.

**Owner/Team:** DIGIT Platform Team

## Architecture

**Tech Stack:**
- Java 25
- Spring Boot 4
- PostgreSQL
- IDGen integration
- HashiCorp Vault optional for sensitive data handling
- `org.digit:tracer` platform library
- Kafka / Redis optional pubsub
- DIGIT tenant migration library

**Core Responsibilities:**
- Create, search, update and delete individual records
- Check individual existence
- Manage individual service configuration
- Generate individual IDs through IDGen when enabled
- Secure sensitive fields with HMAC/Vault configuration
- Publish individual lifecycle events when pubsub is enabled
- Expose canonical APIs using request/response metadata envelopes

**Dependencies:**
- PostgreSQL required
- IDGen optional/recommended
- Vault optional
- Kafka or Redis optional for pubsub events
- OpenTelemetry Collector optional

## Features

- Individual CRUD APIs
- Search and existence APIs
- Individual configuration APIs
- IDGen-backed individual ID generation
- Optional Vault-backed sensitive information handling
- Tenant schema separation support
- Canonical APIs using `requestMetadata`, `ResponseMetadata` and `data`
- Actuator health, metrics and Prometheus endpoints

## Configuration

Main runtime properties are configured in
`src/main/resources/application.properties`.

### Server

| Property | Default | Description |
|---|---|---|
| `server.servlet.context-path` | `${SERVER_CONTEXT_PATH:/individuals}` | Service context path |
| `server.port` | `${SERVER_PORT:8080}` | HTTP port |
| `individual.server.canonical-api-prefix` | `${CANONICAL_API_PREFIX:canonical}` | Canonical route prefix |

### Datasource

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5434}/${DB_NAME:postgres}?sslmode=${DB_SSL_MODE:disable}` | PostgreSQL connection |
| `spring.datasource.username` | `${DB_USER:postgres}` | Database user |
| `spring.datasource.password` | `${DB_PASSWORD:password}` | Database password |
| `spring.datasource.hikari.maximum-pool-size` | `${DB_MAX_OPEN_CONNS:10}` | Maximum pool connections |
| `spring.datasource.hikari.minimum-idle` | `${DB_MAX_IDLE_CONNS:5}` | Minimum idle connections |
| `spring.datasource.hikari.max-lifetime` | `${DB_CONN_MAX_LIFETIME:300}000` | Connection max lifetime; set `DB_CONN_MAX_LIFETIME` in seconds |

### Integrations

| Property | Default | Description |
|---|---|---|
| `individual.idgen.enabled` | `${IDGEN_ENABLED:true}` | Generate `individualId` through IDGen; a local `IND-xxxxxxxx` id is used when disabled |
| `individual.idgen.host` | `${IDGEN_HOST:http://localhost:8100/}` | IDGen base URL |
| `individual.idgen.path` | `${IDGEN_PATH:idgen/v3/generate}` | IDGen generate path |
| `individual.idgen.format` | `${IDGEN_INDIVIDUAL_ID_FORMAT:individual}` | IDGen template code |
| `individual.vault.enabled` | `${VAULT_ENABLED:false}` | Encrypt personal data at rest with Vault Transit |
| `individual.vault.address` | `${VAULT_HOST:http://localhost:8202}` | Vault address |
| `individual.vault.role-id` | `${VAULT_ROLE_ID:}` | Vault AppRole role id (supply via secret) |
| `individual.vault.secret-id` | `${VAULT_SECRET_ID:}` | Vault AppRole secret id (supply via secret) |
| `individual.hmac-secret` | `${HMAC_SECRET:}` | Key for the mobile-number hash; required when Vault is enabled |
| `tracer.http.connectTimeoutMs` | `2000` | Connect timeout for IDGen calls |
| `tracer.http.readTimeoutMs` | `10000` | Request timeout for IDGen calls |

Hosts end with `/` and paths do not start with one; the two are concatenated as-is.

With Vault enabled, `mobileNumber`, `altContactNumber` and AADHAAR `identifierId` are stored
encrypted. The mobile number is also stored as a keyed hash, which is what mobile search and the
mobile uniqueness check match on.

### Pub/Sub

| Property | Default | Description |
|---|---|---|
| `individual.pubsub.enabled` | `${PUBSUB_ENABLED:false}` | Event publishing toggle |
| `tracer.pubsub.type` | `${PUBSUB_TYPE:kafka}` | Backend: `kafka` or `redis` |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BROKERS:localhost:9092}` | Kafka brokers |
| `spring.data.redis.host` / `port` | `${REDIS_HOST:localhost}` / `${REDIS_PORT:6379}` | Redis connection |

| Topic property | Default |
|---|---|
| `individual.pubsub.topics.create-individual` | `${PUBSUB_TOPIC_CREATE_INDIVIDUAL:individual-create-individual}` |
| `individual.pubsub.topics.update-individual` | `${PUBSUB_TOPIC_UPDATE_INDIVIDUAL:individual-update-individual}` |
| `individual.pubsub.topics.delete-individual` | `${PUBSUB_TOPIC_DELETE_INDIVIDUAL:individual-delete-individual}` |
| `individual.pubsub.topics.upsert-config` | `${PUBSUB_TOPIC_UPSERT_CONFIG:individual-upsert-config}` |

### Tenant Migration

| Property | Default | Description |
|---|---|---|
| `digit.tenant-migration.enabled` | `${TENANT_MIGRATION_ENABLED:false}` | Tenant schema separation |
| `digit.tenant-migration.schema-table` | `${TENANT_MIGRATION_SCHEMA_TABLE:individual_schema}` | Flyway history table |
| `digit.tenant-migration.consumer-group` | `${TENANT_MIGRATION_CONSUMER_GROUP:individual-service}` | Kafka consumer group |

## Database

**Tables:**

| Table | Description |
|---|---|
| `individual_v3` | Individual records; `individualId` is unique per tenant |
| `individual_address_v3` | Addresses of an individual |
| `individual_identifier_v3` | Identifiers of an individual; one active identifier per type |
| `individual_document_v3` | Documents of an individual |
| `individual_config_v3` | Per-tenant validation and uniqueness configuration |

Deletes are soft: the individual and its children are marked inactive.

**Migrations** live in `src/main/resources/db/migration`. The service does not run them at startup:

- `public` schema — applied by the db init container (`db/Dockerfile`, `migrate.sh`)
- Tenant schemas — applied by the tenant-migration library when `digit.tenant-migration.enabled=true`

Name search relies on the `pg_trgm` extension, which the migrations install in `public`. Keep
migration scripts schema-unqualified so they apply to both.

## API Reference

**Base path:** `{SERVER_CONTEXT_PATH}/v3` (default `/individuals/v3`)

3.0 routes use request metadata in headers:

| Header | Required on | Description |
|---|---|---|
| `X-Tenant-ID` | All endpoints | Tenant identifier |
| `X-User-ID` | Write endpoints | Audit user |
| `X-Request-ID` | Optional | Request/audit correlation |

### Individual APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/individuals` | Create individual |
| `GET` | `/v3/individuals` | Search individuals |
| `GET` | `/v3/individuals/exists` | Check whether an individual exists |
| `GET` | `/v3/individuals/{id}` | Get individual by ID |
| `PUT` | `/v3/individuals/{id}` | Update individual |
| `DELETE` | `/v3/individuals/{id}` | Delete individual |

`PUT` requires the current `version` and returns `409 ROW_VERSION_MISMATCH` when it is stale.
Addresses, identifiers and documents are replaced by the request: entries with an `id` are updated,
entries without one are added, and existing entries left out are deactivated.

**Search parameters** (`GET /v3/individuals`, all optional):

| Parameter | Description |
|---|---|
| `id`, `individualId`, `userId` | Exact match; comma-separated for several values |
| `givenName` | Case-insensitive "contains" match |
| `mobileNumber` | Exact match |
| `gender` | `MALE`, `FEMALE` or `OTHER` |
| `dateOfBirth` | Exact date, `yyyy-MM-dd` |
| `includeDeleted` | Include soft-deleted records, default `false` |
| `page` | Page number, default `1` |
| `size` | Page size, default `20`, maximum `100` |

The response carries `totalCount`, `page`, `size`, `hasMore` and `individuals`, ordered by creation
time, newest first. `GET /v3/individuals/exists` accepts the same filters (single values) and
returns `{ "exists": true | false }`.

### Configuration APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/configs` | Upsert individual config |
| `GET` | `/v3/configs` | Search individual config |

A tenant config can set `mobileRegex`, `nameRegex` and `uniquenessCriteria` (`mobileNumber`,
`name`). Listed criteria are enforced on create and update with `409 UNIQUE_ENTITY_ERROR`.

Example create request:

```json
{
  "givenName": "Test",
  "familyName": "User",
  "gender": "FEMALE",
  "dateOfBirth": "1990-04-12",
  "mobileNumber": "9999999999",
  "address": [
    { "type": "PERMANENT", "city": "Pune", "pincode": "411001" }
  ],
  "identifiers": [
    { "identifierType": "PAN", "identifierId": "ABCDE1234F" }
  ],
  "documents": [
    { "documentType": "PHOTO", "fileStoreId": "fs-123" }
  ]
}
```

`givenName` is required, as is at least one of `mobileNumber` or `email`. Names accept the letters
A–Z (either case) and spaces.

## Canonical API

Canonical individual APIs are available under:

`{SERVER_CONTEXT_PATH}/v3/{CANONICAL_API_PREFIX}`

Default prefix: `canonical`

Canonical routes mirror the individual and configuration routes above. They use `requestMetadata`
instead of `X-*` request headers, wrap business payloads under `data`, and respond with
`ResponseMetadata` and `data`.

```json
{
  "requestMetadata": {
    "tenantId": "ESMAGICO",
    "ts": 1712830200000,
    "requestId": "req-123",
    "userInfo": { "uuid": "user-1" }
  },
  "data": {
    "givenName": "Test",
    "familyName": "User",
    "mobileNumber": "9999999999"
  }
}
```

## Errors

Errors are returned as a list of `code` / `message` pairs:

```json
[{ "code": "VALIDATION_ERROR", "message": "givenName is required" }]
```

| Status | Codes |
|---|---|
| `400` | `VALIDATION_ERROR`, `INVALID_REQUEST`, `MISSING_HEADER` |
| `404` | `NOT_FOUND` |
| `409` | `UNIQUE_ENTITY_ERROR`, `DUPLICATE_ERROR`, `ROW_VERSION_MISMATCH` |
| `500` | `IDGEN_TEMPLATE_NOT_FOUND` — no IDGen template for `individualId` in the tenant |
| `502` | `DOWNSTREAM_ERROR` — IDGen or Vault failed or timed out |

## Health and Observability

| Endpoint | Description |
|---|---|
| `{SERVER_CONTEXT_PATH}/actuator/health/liveness` | Process is alive |
| `{SERVER_CONTEXT_PATH}/actuator/health/readiness` | Ready to serve, including the database check |
| `{SERVER_CONTEXT_PATH}/actuator/prometheus` | Prometheus metrics |

## Local Run

```bash
cd src/services/individual
mvn spring-boot:run
```

Set database and integration environment variables as needed:

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=postgres
export DB_USER=postgres
export DB_PASSWORD=password
export IDGEN_HOST=http://localhost:8100/
```

Apply migrations to a local database with the Flyway CLI (the same script the init container runs):

```bash
cd src/main/resources/db
DB_URL=jdbc:postgresql://localhost:5432/postgres SCHEMA_TABLE=individual_schema \
FLYWAY_USER=postgres FLYWAY_PASSWORD=password FLYWAY_LOCATIONS=filesystem:$PWD/migration \
FLYWAY_SCHEMAS=public FLYWAY_DEFAULT_SCHEMA=public sh migrate.sh
```

## Tests

```bash
cd src/services/individual
mvn test
```
