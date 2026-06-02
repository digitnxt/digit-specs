# Business Rules — Registry Service

## Cross-Field Rules

### BR-CF-001: Optimistic version lock on data update

**Entities involved:** `RegistryData`, `DataRequest`  
**Rule:** `DataRequest.version` must exactly equal the `version` of the currently active record being updated. An update with a stale or future version number is rejected.  
**Violation response:** 400 — `ValidationError`

---

### BR-CF-002: Version must be present and positive on update

**Entities involved:** `DataRequest`  
**Rule:** `DataRequest.version` must be provided and > 0 when performing a PUT. Omitting it or providing 0 / negative values is rejected.  
**Violation response:** 400 — `ValidationError`

---

### BR-CF-003: SchemaCode must match identifier pattern

**Entities involved:** `SchemaRequest`, path parameter `schemaCode`  
**Rule:** `schemaCode` must match the pattern `[A-Za-z0-9\-_.:]+`. Any character outside that set is rejected.  
**Violation response:** 400 — `ValidationError`

---

### BR-CF-004: Definition required and non-null on every schema write

**Entities involved:** `SchemaRequest`  
**Rule:** `definition` must be present, non-null, and non-empty JSON on both create and update schema operations.  
**Violation response:** 400 — `ValidationError`

---

### BR-CF-005: Only JSON Schema Draft 2020-12 accepted

**Entities involved:** `SchemaRequest.definition`  
**Rule:** When a `$schema` key is present in `definition`, it must be exactly `https://json-schema.org/draft/2020-12/schema`. If absent the service injects the correct value. Any other draft URI causes rejection.  
**Violation response:** 400 — `ValidationError`

---

### BR-CF-006: Webhook fires only when active and URL non-empty

**Entities involved:** `WebhookConfig`, data mutation requests  
**Rule:** A schema-level webhook callback is only dispatched when `webhook.active == true` AND `webhook.url` is non-empty. A request-level `X-Callback-URL` header completely replaces the schema webhook for that single request, regardless of the `active` flag.  
**Violation response:** N/A (webhook silently skipped when conditions not met)

---

## Cross-Schema Rules

### BR-CS-001: Internal x-ref-schema field must exist in target

**Entities involved:** `Schema.XRefSchema`, `RegistryData`, referenced schema's per-schema table  
**Rule:** For each `RefSchema` entry with `external: false`, the value at `fieldPath` in the inbound data payload must match an active record in the referenced schema's table. The matching field defaults to `registryId` unless `refField` is specified. Both the referenced field and value must be non-empty.  
**Violation response:** 400 — `ValidationError`

---

### BR-CS-002: External x-ref-schema must pass remote isExist check

**Entities involved:** `Schema.XRefSchema` (external:true), external registry service  
**Rule:** For each `RefSchema` entry with `external: true`, the service calls `POST <external-registry>/schema/<schemaCode>/_isExist` with `{tenantId, field, value}`. If the external service returns `exists: false` or is unreachable, the data operation is rejected.  
**Violation response:** 400 — `ValidationError`

---

### BR-CS-003: No duplicate values across x-unique field groups

**Entities involved:** `Schema.XUnique`, active records in per-schema table  
**Rule:** For each constraint group (array of field paths), no two simultaneously active records in the same `(tenantId, schemaCode)` table may have identical values for every field in the group. On update the record being updated is excluded from the check. All paths in the group must be present and non-empty in the payload.  
**Violation response:** 409 — `Conflict`

---

### BR-CS-004: x-ref and x-unique fields cannot be missing in payload

**Entities involved:** `Schema.XRefSchema`, `Schema.XUnique`, `DataRequest.data`  
**Rule:** If the data payload is missing a field declared in an `x-ref-schema` entry or missing any field declared in an `x-unique` constraint group, the write is rejected before any DB query.  
**Violation response:** 400 — `ValidationError`

---

### BR-CS-005: Only one active latest schema per tenant and code

**Entities involved:** `schemas` table  
**Rule:** At most one row per `(tenant_id, schema_code)` may have both `is_active=true` and `is_latest=true` simultaneously. Schema creation uses `SELECT … FOR UPDATE` to prevent race conditions.  
**Violation response:** 409 — `Conflict`

---

## Lifecycle Rules

### BR-LC-001: Schema updates create new immutable version rows

**Entities involved:** `Schema`  
**Rule:** Schema definitions are never updated in place. Every `PUT /schema/:schemaCode` that changes the definition, `x-unique`, `x-ref-schema`, `x-indexes`, or `webhook` creates a new row with a monotonically incremented `version`. If the submitted content is identical to the current version, no new row is created (idempotent).  
**Violation response:** N/A (idempotent when unchanged)

---

### BR-LC-002: Data updates create new immutable version rows

**Entities involved:** `RegistryData`  
**Rule:** Data records are never overwritten. Every `PUT` creates a new row with `version = currentVersion + 1`, `is_active = true`, `effective_from = now`. The previous row has `is_active` set to `false` and `effective_to` stamped with the same timestamp.  
**Violation response:** N/A (version violations produce 400)

---

### BR-LC-003: Deletion is soft only — no physical removal

**Entities involved:** `Schema`, `RegistryData`  
**Rule:** `DELETE /schema/:schemaCode` sets `is_active=false` and `is_latest=false` for all rows matching that code. `DELETE /data/:id` sets `is_active=false` and `effective_to=now` on the matching row. All read operations filter `is_active=true`.  
**Violation response:** 404 — `NotFound` (if record absent before delete)

---

### BR-LC-004: Data validation uses schema version stored at write time

**Entities involved:** `RegistryData.SchemaVersion`, `Schema`  
**Rule:** On data create, the `schema_version` stored with the record is the version of the active+latest schema at write time. On data update, validation is performed against the schema version stored on the existing record — not necessarily the current latest schema version.  
**Violation response:** 404 — `NotFound` (if stored schema version no longer exists)

---

### BR-LC-005: Audit log forms tamper-evident hash chain

**Entities involved:** `AuditLog`  
**Rule:** For UPDATE and DELETE operations, the audit manager loads the `payload_hash` of the most recent prior audit entry for the same `(tenantId, subjectType, schemaCode, recordId)` and prepends it to the SHA-256 digest input. CREATE operations start a new chain (empty `previousHash`).  
**Violation response:** 500 — `InternalServerError` (on audit failure)

---

## Cross-Module Rules

### BR-CM-001: IDGen library generates all registryIds on create

**Entities involved:** `RegistryData.RegistryID`, IDGen library  
**Rule:** Every data create operation calls the embedded IDGen library with `templateCode = IDGEN_TEMPLATE_ID` to obtain a stable `registryId`. If IDGen returns an error or an empty ID, the create is rejected.  
**Violation response:** 500 — `InternalServerError`

---

### BR-CM-002: Vault required gates service startup

**Entities involved:** Service configuration, Vault Transit  
**Rule:** If `VAULT_REQUIRED=true` and either `VAULT_ADDRESS` or `VAULT_TOKEN` is empty at startup, the service exits immediately. If both are present but Vault is unreachable, the service also exits.  
**Violation response:** Service startup failure (fatal)

---

### BR-CM-003: Signature verify requires Vault to be configured

**Entities involved:** `_verify` endpoint, Vault Transit  
**Rule:** `GET /_verify` only succeeds when a Vault-backed auditor is configured. When the no-op auditor is active, the endpoint returns 501.  
**Violation response:** 501 — `InternalServerError`

---

### BR-CM-004: Validation webhook errors reject unless fail-open

**Entities involved:** `WebhookConfig`, all mutation operations  
**Rule:** When `VALIDATION_WEBHOOK_ENABLED=true`, any webhook HTTP error or non-2xx response causes the mutation to be rejected, unless `VALIDATION_WEBHOOK_FAIL_OPEN=true`. A 2xx response with `{"allow": false}` or `{"valid": false}` rejects the mutation regardless of `VALIDATION_WEBHOOK_FAIL_OPEN`.  
**Violation response:** 400 — `ValidationError`

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-Id` header | `ValidationError` |
| 400 | Missing `X-User-Id` header | `ValidationError` |
| 400 | Invalid `schemaCode` format | `ValidationError` |
| 400 | Missing or null `definition` | `ValidationError` |
| 400 | Invalid JSON Schema Draft 2020-12 | `ValidationError` |
| 400 | Data fails schema validation | `ValidationError` |
| 400 | x-ref-schema field missing or empty in payload | `ValidationError` |
| 400 | x-ref-schema referenced record absent | `ValidationError` |
| 400 | x-unique fields missing or empty | `ValidationError` |
| 400 | Version mismatch or missing on update | `ValidationError` |
| 400 | Missing `id` query parameter | `ValidationError` |
| 400 | Plugin or webhook validation rejection | `ValidationError` |
| 404 | Schema not found | `NotFound` |
| 404 | Data record not found | `NotFound` |
| 404 | Audit log not found for `_verify` | `NotFound` |
| 409 | Schema with same code already exists | `Conflict` |
| 409 | x-unique constraint violated | `Conflict` |
| 501 | Vault not configured for `_verify` | `InternalServerError` |
| 500 | IDGen failure | `InternalServerError` |
| 500 | Database or internal error | `InternalServerError` |
