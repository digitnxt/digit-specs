# Employee Service (employee)

A DIGIT 3.0 microservice that manages tenant-scoped employee records,
onboarding, status changes and employee jurisdictions. It integrates with IDGen,
Boundary, Individual and optionally Keycloak, and exposes both standard and
canonical APIs.

## Overview

**Service Name:** employee

**Purpose:** Provide employee master-data and jurisdiction management for DIGIT
services, including employee onboarding and employee-to-boundary assignments.

**Owner/Team:** DIGIT Platform Team

## Architecture

**Tech Stack:**
- Java 25
- Spring Boot 4
- PostgreSQL
- IDGen integration
- Boundary service integration
- Individual service integration optional
- Keycloak optional for identity lookups
- `org.digit:tracer` platform library
- Kafka / Redis optional pubsub
- DIGIT tenant migration library

**Core Responsibilities:**
- Create, onboard, search, update and delete employee records
- Patch employee records
- Deactivate and reactivate employees
- Manage employee jurisdictions
- Generate employee codes through IDGen when enabled
- Validate or enrich jurisdiction data through Boundary when enabled
- Publish employee and jurisdiction lifecycle events when pubsub is enabled
- Expose canonical APIs using request/response metadata envelopes

**Dependencies:**
- PostgreSQL required
- IDGen optional/recommended
- Boundary service optional/recommended
- Individual service optional
- Keycloak optional
- Kafka or Redis optional for pubsub events
- OpenTelemetry Collector optional

## Features

- Employee create/search/get/update/delete APIs
- Combined employee onboarding API
- Patch, deactivate and reactivate APIs
- Jurisdiction create/search/get/update APIs
- IDGen-backed employee code generation
- Boundary-backed jurisdiction validation/enrichment
- Tenant schema separation support
- Canonical APIs using `requestMetadata`, `ResponseMetadata` and `data`
- Actuator health, metrics and Prometheus endpoints

## Configuration

Main runtime properties are configured in
`src/main/resources/application.properties`.

### Server

| Property | Default | Description |
|---|---|---|
| `server.servlet.context-path` | `${SERVER_CONTEXT_PATH:/employee-java}` | Service context path |
| `server.port` | `${SERVER_PORT:8081}` | HTTP port |
| `employee.server.canonical-api-prefix` | `${CANONICAL_API_PREFIX:canonical}` | Canonical route prefix |

### Datasource

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5434}/${DB_NAME:postgres}?sslmode=${DB_SSL_MODE:disable}` | PostgreSQL connection |
| `spring.datasource.username` | `${DB_USER:postgres}` | Database user |
| `spring.datasource.password` | `${DB_PASSWORD:password}` | Database password |

The connection pool uses HikariCP defaults (10 connections); override with
`SPRING_DATASOURCE_HIKARI_*` environment variables if needed.

### Integrations

| Property | Default | Description |
|---|---|---|
| `employee.idgen.enabled` | `${IDGEN_ENABLED:true}` | Enable IDGen integration |
| `employee.idgen.host` | `${IDGEN_HOST:http://localhost:8100/}` | IDGen base URL |
| `employee.idgen.path` | `${IDGEN_PATH:idgen/v3/generate}` | IDGen generate path |
| `employee.idgen.idgen-name` | `${IDGEN_NAME:EmployeeCode}` | Employee IDGen template/code |
| `employee.boundary.enabled` | `${BOUNDARY_ENABLED:true}` | Enable Boundary validation |
| `employee.boundary.base-url` | `${BOUNDARY_HOST:http://localhost:8095/}` | Boundary base URL |
| `employee.boundary.path` | `${BOUNDARY_PATH:boundary/v3/relationship}` | Boundary relationship path |
| `employee.individual.enabled` | `${INDIVIDUAL_ENABLED:false}` | Enable Individual validation |
| `employee.individual.host` | `${INDIVIDUAL_HOST:http://localhost:8086/}` | Individual base URL |
| `employee.individual.path` | `${INDIVIDUAL_PATH:individuals/v3/individuals}` | Individual collection path |
| `employee.keycloak.enabled` | `${KEYCLOAK_ENABLED:false}` | Enable Keycloak user validation |
| `employee.keycloak.base-url` | `${KEYCLOAK_BASE_URL:https://digit-lts.digit.org/keycloak}` | Keycloak base URL |
| `employee.keycloak.client-id` | `${KEYCLOAK_EMPLOYEE_IAM_CLIENT_ID:}` | Service account for read-only realm lookups |
| `employee.keycloak.client-secret` | `${KEYCLOAK_EMPLOYEE_IAM_CLIENT_SECRET:}` | Service account secret (supply via secret) |
| `tracer.http.connectTimeoutMs` | `2000` | Connect timeout for downstream calls |
| `tracer.http.readTimeoutMs` | `10000` | Request timeout for downstream calls |

Hosts end with `/` and paths do not start with one; the two are concatenated as-is.

### Pub/Sub

| Property | Default | Description |
|---|---|---|
| `employee.pubsub.enabled` | `${PUBSUB_ENABLED:false}` | Event publishing toggle |
| `tracer.pubsub.type` | `${PUBSUB_TYPE:kafka}` | Backend: `kafka` or `redis` |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BROKERS:localhost:9092}` | Kafka brokers |
| `spring.data.redis.host` / `port` | `${REDIS_HOST:localhost}` / `${REDIS_PORT:6379}` | Redis connection |

| Topic property | Default |
|---|---|
| `employee.pubsub.topics.create-employee` | `${PUBSUB_TOPIC_CREATE_EMPLOYEE:employee-create-employee}` |
| `employee.pubsub.topics.update-employee` | `${PUBSUB_TOPIC_UPDATE_EMPLOYEE:employee-update-employee}` |
| `employee.pubsub.topics.delete-employee` | `${PUBSUB_TOPIC_DELETE_EMPLOYEE:employee-delete-employee}` |
| `employee.pubsub.topics.create-jurisdiction` | `${PUBSUB_TOPIC_CREATE_JURISDICTION:employee-create-jurisdiction}` |
| `employee.pubsub.topics.update-jurisdiction` | `${PUBSUB_TOPIC_UPDATE_JURISDICTION:employee-update-jurisdiction}` |

### Tenant Migration

| Property | Default | Description |
|---|---|---|
| `digit.tenant-migration.enabled` | `${TENANT_MIGRATION_ENABLED:false}` | Tenant schema separation |
| `digit.tenant-migration.schema-table` | `${TENANT_MIGRATION_SCHEMA_TABLE:employee_schema}` | Flyway history table |
| `digit.tenant-migration.consumer-group` | `${TENANT_MIGRATION_CONSUMER_GROUP:employee-service}` | Kafka consumer group |

## Database

**Tables:**

| Table | Description |
|---|---|
| `employee_v3` | Employee records, unique on `(tenant_id, code)` |
| `employee_jurisdiction_v3` | Boundary assignments per employee; removed with the employee (`ON DELETE CASCADE`) |

**Migrations** live in `src/main/resources/db/migration`. The service does not run them at startup:

- `public` schema — applied by the db init container (`db/Dockerfile`, `migrate.sh`)
- Tenant schemas — applied by the tenant-migration library when `digit.tenant-migration.enabled=true`

Keep migration scripts schema-unqualified so they apply to both.

## API Reference

**Base path:** `{SERVER_CONTEXT_PATH}/v3` (default `/employee-java/v3`)

3.0 routes use request metadata in headers:

| Header | Required on | Description |
|---|---|---|
| `X-Tenant-ID` | All endpoints | Tenant identifier |
| `X-User-ID` | Create, onboard, update, patch, deactivate, reactivate | Audit user |
| `X-Request-ID` | Optional | Request/audit correlation |

### Employee APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/employees` | Create employee |
| `POST` | `/v3/employees/onboard` | Onboard employee |
| `GET` | `/v3/employees` | Search employees |
| `GET` | `/v3/employees/{id}` | Get employee by ID |
| `PUT` | `/v3/employees/{id}` | Update employee |
| `DELETE` | `/v3/employees/{id}` | Delete employee |
| `PATCH` | `/v3/employees/{id}` | Patch employee |
| `POST` | `/v3/employees/{id}/deactivate` | Deactivate employee |
| `POST` | `/v3/employees/{id}/reactivate` | Reactivate employee |

Employee responses include all of the employee's jurisdictions. `PUT` and `PATCH` require the
current `version` and return `409 ROW_VERSION_MISMATCH` when it is stale.

**Search parameters** (`GET /v3/employees`, all optional, list values comma-separated):

| Parameter | Description |
|---|---|
| `ids` | Employee UUIDs |
| `codes` | Employee codes |
| `userIds` | Keycloak user ids |
| `role` | Keycloak realm role; matches employees whose user holds it |
| `statuses`, `employeeTypes`, `departments`, `designations` | Exact-match filters |
| `dateOfAppointmentFrom`, `dateOfAppointmentTo` | Date range, `yyyy-MM-dd` |
| `isActive` | `true` / `false` |
| `limit` | 1–100, default `10` |
| `offset` | Default `0` |

Results are ordered by creation time, newest first.

### Jurisdiction APIs

| Method | Path | Description |
|---|---|---|
| `POST` | `/v3/employees/{employeeId}/jurisdictions` | Create jurisdiction |
| `GET` | `/v3/employees/{employeeId}/jurisdictions` | Search jurisdictions |
| `GET` | `/v3/employees/{employeeId}/jurisdictions/{jurisdictionId}` | Get jurisdiction |
| `PUT` | `/v3/employees/{employeeId}/jurisdictions/{jurisdictionId}` | Update jurisdiction |

Jurisdiction search accepts `ids`, `isActive`, `limit` (1–100, default `10`) and `offset`.

Example create request (a JSON array of 1–100 employees; `code` is generated when omitted):

```json
[
  {
    "code": "EMP-001",
    "employeeType": "PERMANENT",
    "department": "REVENUE",
    "designation": "CLERK",
    "status": "ACTIVE",
    "dateOfAppointment": "2024-01-15T10:30:00+05:30",
    "jurisdictions": [
      {
        "boundaryRelation": [
          { "code": "CITY-01", "boundaryType": "CITY", "hierarchyType": "ADMIN" }
        ]
      }
    ]
  }
]
```

Example onboarding request (one user, one individual and one employee):

```json
{
  "user": {
    "mobileNumber": "9999999999",
    "password": "********",
    "email": "employee@example.org",
    "firstName": "Test",
    "lastName": "Employee",
    "roles": ["EMPLOYEE"]
  },
  "individual": {
    "givenName": "Test",
    "familyName": "Employee",
    "mobileNumber": "9999999999"
  },
  "employee": {
    "employeeType": "PERMANENT",
    "department": "REVENUE",
    "designation": "CLERK",
    "jurisdictions": []
  }
}
```

## Canonical API

Canonical employee APIs are available under:

`{SERVER_CONTEXT_PATH}/v3/{CANONICAL_API_PREFIX}`

Default prefix: `canonical`

Canonical routes mirror the employee and jurisdiction routes above. They use `requestMetadata`
instead of `X-*` request headers, wrap business payloads under `data`, and respond with
`ResponseMetadata` and `data`.

Example canonical create request (`data` is the same array as the create request):

```json
{
  "requestMetadata": {
    "tenantId": "ESMAGICO",
    "ts": 1712830200000,
    "requestId": "req-123",
    "userInfo": { "uuid": "user-1" }
  },
  "data": [
    {
      "code": "EMP-001",
      "employeeType": "PERMANENT",
      "department": "REVENUE",
      "designation": "CLERK"
    }
  ]
}
```

## Errors

Errors are returned as a list of `code` / `message` pairs:

```json
[{ "code": "VALIDATION_ERROR", "message": "employeeType is required" }]
```

| Status | Codes |
|---|---|
| `400` | `VALIDATION_ERROR`, `INVALID_REQUEST`, `INVALID_UUID` |
| `404` | `NOT_FOUND`, `EMPLOYEE_NOT_FOUND` |
| `409` | `EMPLOYEE_EXISTS`, `ROW_VERSION_MISMATCH`, `EMPLOYEE_ALREADY_ACTIVE`, `EMPLOYEE_ALREADY_INACTIVE`, `CONFLICT` |
| `502` | `DOWNSTREAM_ERROR` — IDGen, Boundary, Individual or Keycloak failed or timed out |

## Health and Observability

| Endpoint | Description |
|---|---|
| `{SERVER_CONTEXT_PATH}/actuator/health/liveness` | Process is alive |
| `{SERVER_CONTEXT_PATH}/actuator/health/readiness` | Ready to serve, including the database check |
| `{SERVER_CONTEXT_PATH}/actuator/prometheus` | Prometheus metrics |

## Local Run

```bash
cd src/services/employee
mvn spring-boot:run
```

Set database and integration environment variables as needed:

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=postgres
export DB_USER=postgres
export DB_PASSWORD=password
export SERVER_CONTEXT_PATH=/employee
export IDGEN_HOST=http://localhost:8100/
export BOUNDARY_HOST=http://localhost:8095/
```

Apply migrations to a local database with the Flyway CLI (the same script the init container runs):

```bash
cd src/main/resources/db
DB_URL=jdbc:postgresql://localhost:5432/postgres SCHEMA_TABLE=employee_schema \
FLYWAY_USER=postgres FLYWAY_PASSWORD=password FLYWAY_LOCATIONS=filesystem:$PWD/migration \
FLYWAY_SCHEMAS=public FLYWAY_DEFAULT_SCHEMA=public sh migrate.sh
```

## Tests

```bash
cd src/services/employee
mvn test
```
