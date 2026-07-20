# Employee Service

Manages staff records (employees) and their **jurisdictions** for the DIGIT
v3 platform. An employee links a Keycloak user (`userId`) and an individual
profile (`individualId`) to an employment record, plus zero or more
jurisdiction assignments that scope the employee's geographical authority.

Schema-separated: each tenant gets its own PostgreSQL schema; the
`tenantdb.GinMiddleware` switches `search_path` per request.

---

## Base path

| Environment | Base URL |
|---|---|
| Local (port-forwarded) | `http://localhost:8080/employee/v3` |
| LTS (Kong gateway)     | `https://digit-lts.digit.org/employee/v3` |
| Configurable via       | `SERVER_CONTEXT_PATH` (default `/employee`) |

---

## API surface

### Employees

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/employees` | Create one or more employees. **Body is a JSON array** (1–100 records). All-or-nothing — any validation failure rolls back the entire batch. |
| `GET`  | `/employees` | Search. Filters: `uuids`, `codes`, `departments`, `designations`, `phone`, `isActive`. Paginated by `limit` / `offset`. |
| `GET`  | `/employees/{id}` | Get one (with embedded jurisdictions). |
| `PUT`  | `/employees/{id}` | Full replace. |
| `PATCH` | `/employees/{id}` | Partial update. |
| `DELETE` | `/employees/{id}` | Hard delete (irreversible). 409 if jurisdictions are still attached. |
| `POST` | `/employees/{id}/deactivate` | Soft deactivate — `isActive = false`. |
| `POST` | `/employees/{id}/reactivate` | Restore — `isActive = true`. |

### Jurisdictions

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/jurisdictions` | Create a jurisdiction assignment for an employee. |
| `GET`  | `/jurisdictions` | Search. Filter by `employeeId`, `isActive`. |
| `GET`  | `/jurisdictions/{id}` | Get one. |
| `PUT`  | `/jurisdictions/{id}` | Full replace of `boundaryRelation` / `isActive`. `employeeId`, `tenantId`, `id` are immutable. |

### Operational

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/health` | Liveness probe. |
| `POST` | `/internal/migrate` | Trigger per-tenant migration. Only registered when `SCHEMA_SEPARATION_MODE=true`. |

---

## Headers contract

| Header | Required | Notes |
|---|---|---|
| `X-Tenant-ID` | Required | Drives the search_path switch. Kong injects this. |
| `X-User-ID` | Required on writes | Audit-author. No `system` fallback. |
| `Content-Type: application/json` | Required on POST / PUT / PATCH | |
| `Authorization: Bearer <token>` | Validated by Kong | The service trusts the gateway. |

---

## Error envelope

Every non-2xx response is a JSON array of error objects:

```json
[
  {
    "code":        "EMPLOYEE_CODE_EXISTS",
    "message":     "Employee code already exists for this tenant",
    "description": "ERROR: duplicate key value violates unique constraint ..."
  }
]
```

| Status | Code | When |
|---|---|---|
| `400` | `INVALID_REQUEST` / `INVALID_UUID` | Bad body / bad path UUID. |
| `400` | `VALIDATION_ERROR` | Field or business-rule failure. |
| `404` | `NOT_FOUND` | Employee or jurisdiction not present. |
| `409` | `EMPLOYEE_CODE_EXISTS` | Duplicate `code` per tenant. |
| `409` | `JURISDICTION_EXISTS` | Equivalent jurisdiction already attached. |
| `500` | `DATABASE_ERROR` / `INTERNAL_ERROR` | Server-side failure. |

---

## Jurisdiction shape (boundaryRelation)

Each jurisdiction holds a `boundaryRelation` array. Each entry carries
**three** fields (the boundary service validates the triple — invalid
combinations 400):

```json
{
  "code":          "WARD-NBO-001",   // tenant-defined identifier
  "boundaryType":  "Ward",           // the label the code resolves to
  "hierarchyType": "ADMIN"           // which hierarchy the code belongs to
}
```

---

## Example: create employee (with embedded jurisdiction)

```bash
curl -sS -X POST 'https://digit-lts.digit.org/employee/v3/employees' \
  -H 'X-Tenant-ID: pg' \
  -H 'X-User-ID: 00000000-0000-0000-0000-000000000001' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer '"$ACCESS_TOKEN" \
  -d '[
    {
      "userId":         "usr-uuid-001",
      "individualId":   "ind-uuid-001",
      "employeeType":   "PERMANENT",
      "department":     "FINANCE",
      "designation":    "ACCOUNTS_OFFICER",
      "dateOfAppointment": "2025-01-01T10:00:00Z",
      "status":         "EMPLOYED",
      "isActive":       true,
      "jurisdictions": [
        {
          "boundaryRelation": [
            { "code": "WARD-NBO-001", "boundaryType": "Ward", "hierarchyType": "ADMIN" }
          ],
          "isActive": true
        }
      ]
    }
  ]'
```

Notes:
- Body is a JSON array even for a single record.
- Omitting `code` makes the server auto-generate one.
- Jurisdictions can be passed inline (as above) or created later via
  `POST /jurisdictions`.

## Example: create standalone jurisdiction

```bash
curl -sS -X POST 'https://digit-lts.digit.org/employee/v3/jurisdictions' \
  -H 'X-Tenant-ID: pg' \
  -H 'X-User-ID: 00000000-0000-0000-0000-000000000001' \
  -H 'Content-Type: application/json' \
  -d '{
    "employeeId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "boundaryRelation": [
      { "code": "WARD-NBO-001", "boundaryType": "Ward", "hierarchyType": "ADMIN" },
      { "code": "WARD-NBO-002", "boundaryType": "Ward", "hierarchyType": "ADMIN" }
    ],
    "isActive": true
  }'
```

## Example: deactivate / reactivate

```bash
curl -sS -X POST 'https://digit-lts.digit.org/employee/v3/employees/'"$ID"'/deactivate' \
  -H 'X-Tenant-ID: pg' -H 'X-User-ID: '"$USER_ID"

curl -sS -X POST 'https://digit-lts.digit.org/employee/v3/employees/'"$ID"'/reactivate' \
  -H 'X-Tenant-ID: pg' -H 'X-User-ID: '"$USER_ID"
```

---

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port. |
| `SERVER_CONTEXT_PATH` | `/employee` | Mount path. |
| `LOG_LEVEL` | `info` | Log level. |
| `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` / `DB_NAME` | — | PostgreSQL. |
| `DB_SSL_MODE` | `disable` | Set `require` in prod. |
| `INDIVIDUAL_HOST` | — | URL of the individual service (used to validate `individualId`). |
| `INDIVIDUAL_ENABLED` | `true` | Disable the cross-service check when running standalone. |
| `BOUNDARY_HOST` | — | URL of the boundary service (used to validate `boundaryRelation` triples). |
| `BOUNDARY_ENABLED` | `true` | |
| `KEYCLOAK_BASE_URL` | — | Used to validate `userId`. |
| `KEYCLOAK_ENABLED` | `true` | |
| `IDGEN_HOST` / `IDGEN_PATH` / `IDGEN_NAME` | — | Auto-generated `code` source. |
| `SCHEMA_SEPARATION_MODE` | `false` | Per-tenant schemas + Flyway. |
| `MIGRATION_FLYWAY_LOCATIONS` | `filesystem:.db/migrations` | |
| `MIGRATION_SCRIPT_PATH` | `.db/migrate.sh` | |
| `MIGRATION_FLYWAY_BIN` | `flyway` | |
| `MIGRATION_SCHEMA_TABLE` | `employee_schema` | |
| `PUBSUB_ENABLED` | `true` | Outbound events + migration consumer. |
| `PUBSUB_TYPE` | `kafka` | `kafka` or `redis`. |
| `KAFKA_BROKERS` | `localhost:9092` | |
| `PUBSUB_TOPIC_CREATE_EMPLOYEE` | `employee-create-employee` | |
| `PUBSUB_TOPIC_UPDATE_EMPLOYEE` | `employee-update-employee` | |
| `PUBSUB_TOPIC_DELETE_EMPLOYEE` | `employee-delete-employee` | |
| `PUBSUB_TOPIC_CREATE_JURISDICTION` | `employee-create-jurisdiction` | |
| `PUBSUB_TOPIC_UPDATE_JURISDICTION` | `employee-update-jurisdiction` | |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | OTel collector. |

---

## Local development

```bash
# Apply control-plane migrations
./db/migrate.sh up

# Run
go run ./cmd/server
```

The service depends on:
- **PostgreSQL** — primary store.
- **Individual service** — validates `individualId` on employee create / update.
- **Keycloak** — validates `userId`.
- **Boundary service** — validates `boundaryRelation` triples (code +
  boundaryType + hierarchyType).
- **IDGen service** — auto-generates employee `code`.

Each cross-service call can be disabled with the corresponding `_ENABLED=false`
env var when running standalone.

---

## Postman collection

[employee-v3.postman_collection.json](https://github.com/digitnxt/digit3/blob/master/src/services/employee/employee-v3.postman_collection.json)
ships with every endpoint above, organized into:

| Folder | Endpoints |
|---|---|
| Operational | `/health` |
| Employees | Create / Get / Search / Update / Patch / Delete / Deactivate / Reactivate |
| Jurisdictions | Create / Get / Search / Replace |

Variables:

| Variable | Default |
|---|---|
| `baseUrl` | `http://localhost:8080/employee/v3` |
| `tenantId` | `pg` |
| `userId` | `00000000-0000-0000-0000-000000000001` |
| `accessToken` | — |
| `individualId` | UUID of an existing individual (paste from individual service) |
| `boundaryCode`, `boundaryType`, `hierarchyType` | Defaults for jurisdiction examples |
| `employeeId`, `jurisdictionId` | captured from the respective Create requests |

---

## Observability

- **Logs** — zerolog JSON. Trace and span IDs interleaved when OTel is on.
- **Tracing** — OpenTelemetry; HTTP spans, GORM spans, outbound calls to
  individual / boundary / keycloak / idgen.
- **Metrics** — request count, latency, DB op duration, cross-service call
  success rates.

---

## Schema migrations

- **Control plane** — `db/migrations/` runs once against the public schema.
- **Per-tenant** — when `SCHEMA_SEPARATION_MODE=true`, the service subscribes
  to the account service's `tenant-create` event and runs the migration set
  against each new tenant schema.
- **Manual trigger** — `POST /internal/migrate {"tenantId":"<tenant>"}`.

---

## Related services

- **Account** — tenant-create event source.
- **Individual** — `individualId` validation.
- **Boundary** — `boundaryRelation` triple validation.
- **Keycloak** — `userId` validation.
- **IDGen** — auto-generated employee `code`.
- **AccessControl** — RBAC + JBAC rules that govern who can do what.
