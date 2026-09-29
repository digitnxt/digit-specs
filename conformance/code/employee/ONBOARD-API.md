# Employee Onboarding API

**One call to create a login user + person record + employee**, instead of three separate API
calls. It provisions a **Keycloak user**, an **Individual** (person profile), and an **Employee**,
and links them together by the new user's id.

## Endpoint
```
POST /employee/v3/employees/onboard
```

## Headers
| Header | Source | Notes |
|---|---|---|
| `Authorization: Bearer <token>` | caller | Required. Must have `manage-users` in the realm (the tenant admin/superuser token does). |
| `X-Tenant-ID` | set by API gateway from the token | e.g. `MAD` |
| `X-User-ID` | set by API gateway from the token | audit actor |
| `Content-Type: application/json` | | |

## Request body
```jsonc
{
  "user": {                         // → Keycloak login user
    "mobileNumber": "9812345678",   // required — becomes the username (login id)
    "password":     "Secret@123",   // required
    "email":        "aisha@corp.example",   // optional
    "firstName":    "Aisha",        // optional
    "lastName":     "Khan",         // optional
    "roles":        ["EMPLOYEE"]    // realm roles to assign (must already exist)
  },
  "individual": {                   // → Individual (person) service, passed through as-is
    "givenName":    "Aisha",        // required
    "gender":       "FEMALE",       // required
    "mobileNumber": "9812345678",
    "email":        "aisha@home.example",
    "identifiers":  [{ "identifierType": "PAN", "identifierId": "ABCDE1234F" }]
    // address[], documents[] etc. also supported
  },
  "employee": {                     // → Employee record
    "employeeType": "PERMANENT",    // required
    "department":   "FINANCE",      // required
    "designation":  "ACCOUNTS_OFFICER", // required
    "jurisdictions": [
      { "boundaryRelation": [
          { "code": "STATE1_1", "boundaryType": "state", "hierarchyType": "state-district-hierarchy" }
      ]}
    ]
  }
}
```
The server injects the new Keycloak `userId` into both the individual and employee records — the
caller does **not** supply `userId`/`individualId`.

## Success — `201 Created`
```jsonc
{
  "user":       { "id": "<keycloak-uuid>", "username": "9812345678", "roles": ["EMPLOYEE"] },
  "individual": { "id": "<uuid>", "individualId": "IND-MAD-00107", "...": "full individual" },
  "employee":   { "id": "<uuid>", "code": "EMP-MAD-00003", "userId": "<keycloak-uuid>",
                  "individualId": "<uuid>", "...": "full employee" }
}
```
The same Keycloak `userId` appears on all three, linking them.

## Behaviour & guarantees
- **Ordered creation:** user → individual → employee.
- **All-or-nothing (compensating rollback):** if any step fails, everything already created is undone
  — the Keycloak user is deleted and the individual is soft-deleted. No orphans.
- **Roles** are validated to exist before the user is created; the caller can only assign roles their
  own token is permitted to grant (Keycloak-enforced).
- `username` = `mobileNumber` (the login identifier) and is immutable once created.

## Error responses
| Status | When |
|---|---|
| `400 INVALID_REQUEST` | missing/invalid field, unknown role, or bad individual/employee payload |
| `401 UNAUTHORIZED` | missing bearer token |
| `403 FORBIDDEN` | caller not permitted to assign a requested role |
| `409 CONFLICT` | mobile number/email already registered, or duplicate individual/employee |
| `502 DOWNSTREAM_ERROR` | Keycloak / individual / boundary service unavailable |

## Sample curl (through the API gateway)
```bash
curl -s -X POST 'https://digit-lts.digit.org/employee/v3/employees/onboard' \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  --data '{
    "user": { "mobileNumber": "9812345678", "password": "Secret@123", "email": "aisha@corp.example", "firstName": "Aisha", "lastName": "Khan", "roles": ["EMPLOYEE"] },
    "individual": { "givenName": "Aisha", "gender": "FEMALE", "mobileNumber": "9812345678", "identifiers": [{ "identifierType": "PAN", "identifierId": "ABCDE1234F" }] },
    "employee": { "employeeType": "PERMANENT", "department": "FINANCE", "designation": "ACCOUNTS_OFFICER",
      "jurisdictions": [{ "boundaryRelation": [{ "code": "STATE1_1", "boundaryType": "state", "hierarchyType": "state-district-hierarchy" }] }] }
  }'
```
> When calling the service directly (not through the gateway), also add
> `-H 'X-Tenant-ID: MAD' -H 'X-User-ID: <uuid>'`, since the gateway is what normally derives those
> from the token.
