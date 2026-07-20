# Access Control Service

A high-performance, centralized Role-Based Access Control (RBAC) microservice built with Go. This service manages access control rules and is designed to be consumed by a Kong gateway plugin for fast, edge-based decision-making.

## Overview

The Access Control Service provides:
- **Centralized Rule Management:** Store and manage RBAC rules in PostgreSQL
- **API Endpoints:** RESTful APIs for rule CRUD operations and rule validation
- **Kong Integration:** Optimized endpoints for Kong plugins to fetch and cache rulesets
- **Version Tracking:** Global version hashing to detect ruleset changes
- **Multi-Tenant Support:** Tenant-aware rule management via `X-Tenant-ID` header

The system works in conjunction with a Kong RBAC plugin that:
- Fetches all rules from this service
- Caches them in memory for low-latency decisions
- Evaluates incoming API requests against cached rules

---

## Rule Definition

An RBAC Rule defines a single permission. The structure is as follows:

| Field       | Type           | Description                                                                                                                              |
|-------------|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `id`        | `UUID`         | Unique identifier for the rule (auto-generated).                                                                                         |
| `tenantId`  | `string`       | The tenant to which this rule applies. The Kong plugin will only use rules that match the tenant of the incoming request.                  |
| `roleNames` | `string[]`     | A list of roles that this rule applies to.                                                                                               |
| `httpMethod`| `string`       | The HTTP method (e.g., `GET`, `POST`, `*`). `*` acts as a wildcard for any method.                                                        |
| `path`      | `string`       | The URL path to match. It supports parameters like `{uuid}` and a trailing wildcard `/*`.                                                  |
| `effect`    | `string`       | The action to take: `ALLOW` or `DENY`.                                                                                                   |
| `priority`  | `integer`      | The rule's priority. **A lower number means higher priority.** Defaults to `100` for ALLOW and `10` for DENY if not specified.            |
| `description`| `string`      | An optional description of the rule's purpose.                                                                                           |
| `enabled`   | `boolean`      | Whether the rule is currently active.                                                                                                    |

---

## Decision Logic

When an API request arrives at the Kong gateway, the RBAC plugin finds the best matching rule by using a strict sorting order. This avoids ambiguity when multiple rules could apply to a single request.

The candidate rules are sorted to find the **single best match** based on the following criteria, in order:

1.  **Priority (Ascending):** The rule with the lowest `priority` number wins. For example, a rule with priority `50` will always be chosen over a rule with priority `60`, regardless of any other factors.
2.  **Specificity (Descending):** If two rules have the *same* priority, the one with the more specific path wins. A static path is more specific than a wildcard. For example, `/filestore/files/upload` is more specific than `/filestore/files/*`.
3.  **Effect:** If both priority and specificity are identical, a `DENY` rule is chosen over an `ALLOW` rule.

Once the single winning rule is determined, the plugin checks if the user's JWT contains any of the roles in the rule's `roleNames`. If it does, the rule's `effect` (`ALLOW` or `DENY`) is enforced. If not, the plugin moves to the next-best rule and repeats the process. If no matching rule is found for the user's roles, access is denied by default.

---

## API Endpoints

All endpoints are prefixed with `/rbac`.

### Rule Management
- `POST /rules`: Creates a new RBAC rule.
- `GET /rules/`: Lists all rules for a given tenant (sent via `X-Tenant-ID` header). Supports pagination via `?page=` and `?size=` query parameters.
- `GET /rules/{id}`: Retrieves a single rule by its ID.
- `PUT /rules/{id}`: Updates a rule.
- `DELETE /rules/{id}`: Deletes a rule.
- `POST /rules/validate`: Validates a rule payload without creating it.

### Internal Endpoints (for Kong Plugin)
These endpoints are designed for the Kong plugin to efficiently load the ruleset.

- `GET /internal/rules`: Fetches the entire ruleset for all tenants. Supports pagination.
- `GET /internal/rules/version`: Returns the global version hash (MD5) of the entire ruleset. The Kong plugin uses this to check if it needs to reload its cache.

---

## Configuration

The service is configured using environment variables:

| Variable        | Description          | Default        |
|-----------------|----------------------|-----------------|
| `SERVER_PORT`   | Server port          | `8080`          |
| `DB_HOST`       | Database host        | `localhost`     |
| `DB_PORT`       | Database port        | `5432`          |
| `DB_USER`       | Database user        | `postgres`      |
| `DB_PASSWORD`   | Database password    | `postgres`      |
| `DB_NAME`       | Database name        | `accesscontrol` |
| `DB_SSL_MODE`   | Database SSL mode    | `disable`       |
| `LOG_LEVEL`     | Logging level        | `info`          |

---

## Running the Service

### Prerequisites

- Go 1.24 or later
- Docker and Docker Compose
- PostgreSQL 12+ (if running without Docker)
- Python 3.8+ with `requests` library (for seeding database)

### Local Development (with Docker)

Start the service and PostgreSQL database:

```bash
docker-compose up --build
```

The service will be available at `http://localhost:8080`.

### Local Development (without Docker)

1. Ensure PostgreSQL is running and accessible
2. Set environment variables:
   ```bash
   export DB_HOST=localhost
   export DB_PORT=5432
   export DB_USER=postgres
   export DB_PASSWORD=postgres
   export DB_NAME=accesscontrol
   export SERVER_PORT=8080
   ```
3. Run the service:
   ```bash
   go run ./cmd/server/main.go
   ```

### Database Migrations

Migrations are automatically applied on service startup. Migration files are located in `db/migrations/`.

### Seeding the Database

Use the `add_manual_rules.py` script to populate the database with a complete set of RBAC rules:

```bash
# Install dependencies
pip install requests

# Run the seeding script
export RBAC_URL="http://localhost:8080"
python3 add_manual_rules.py
```

This script will:
- Delete all existing rules for the configured tenant
- Add a comprehensive set of rules for all services
- Print status for each operation



## Project Structure

```
src/services/accesscontrol/
├── cmd/server/              # Application entry point
├── internal/
│   ├── config/              # Configuration management
│   ├── constants/           # Application constants
│   ├── handler/             # HTTP request handlers
│   ├── model/               # Data models
│   ├── repository/          # Database operations
│   ├── routes/              # Route definitions
│   ├── service/             # Business logic
│   └── validator/           # Input validation
├── db/
│   ├── migrations/          # Database migration scripts
│   └── Dockerfile           # Database container setup
├── Dockerfile               # Application container setup
├── go.mod / go.sum          # Go dependencies
├── add_manual_rules.py      # Database seeding script
└── README.md                # This file
```

## API Examples

### Create a Rule

```bash
curl -X POST http://localhost:8080/rbac/rules \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: RJ" \
  -d '{
    "roleNames": ["ADMIN"],
    "httpMethod": "POST",
    "path": "/filestore/files/upload",
    "effect": "ALLOW",
    "priority": 50,
    "description": "Allow admins to upload files",
    "enabled": true
  }'
```

### List Rules

```bash
curl -X GET "http://localhost:8080/rbac/rules?page=1&size=10" \
  -H "X-Tenant-ID: RJ"
```

### Get Rule by ID

```bash
curl -X GET http://localhost:8080/rbac/rules/{id} \
  -H "X-Tenant-ID: RJ"
```

### Update a Rule

```bash
curl -X PUT http://localhost:8080/rbac/rules/{id} \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: RJ" \
  -d '{
    "roleNames": ["ADMIN", "SUPERUSER"],
    "httpMethod": "POST",
    "path": "/filestore/files/upload",
    "effect": "ALLOW",
    "priority": 50,
    "description": "Allow admins and superusers to upload files",
    "enabled": true
  }'
```

### Delete a Rule

```bash
curl -X DELETE http://localhost:8080/rbac/rules/{id} \
  -H "X-Tenant-ID: RJ"
```

### Validate a Rule

```bash
curl -X POST http://localhost:8080/rbac/rules/validate \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: RJ" \
  -d '{
    "roleNames": ["ADMIN"],
    "httpMethod": "GET",
    "path": "/filestore/files/*",
    "effect": "ALLOW",
    "priority": 100,
    "description": "Allow admins to list files",
    "enabled": true
  }'
```

### Fetch All Rules (Kong Plugin)

```bash
curl -X GET http://localhost:8080/rbac/internal/rules
```

### Get Ruleset Version Hash

```bash
curl -X GET http://localhost:8080/rbac/internal/rules/version
```

## Testing

### Using Postman

Import the provided Postman collection:
```bash
accesscontrol.postman_collection.json
```

### Manual Testing

Use the provided shell scripts or curl commands to test endpoints. Ensure the service is running and the database is seeded with rules.

## Troubleshooting

### Database Connection Issues

- Verify PostgreSQL is running: `docker ps | grep postgres`
- Check environment variables are set correctly
- Ensure database user has proper permissions

### Port Already in Use

If port 8080 is already in use, change the `SERVER_PORT` environment variable:
```bash
export SERVER_PORT=8081
go run ./cmd/server/main.go
```

### Rules Not Matching

- Verify the tenant ID matches the rule's tenant
- Check rule priority and specificity
- Ensure the rule is enabled (`enabled: true`)
- Review the decision logic section above

## Contributing

When adding new features or modifying the service:
1. Update relevant documentation
2. Add database migrations if schema changes
3. Test with the provided Postman collection
4. Update the seeding script if new default rules are needed

## License

See LICENSE file for details.
