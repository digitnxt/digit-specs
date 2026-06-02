# Business Rules — Localization Service

---

## Cross-Field Rules

### Cross-field: Composite key uniqueness

**Entities involved:** Message (`tenant_id`, `locale`, `module`, `code`)  
**Rule:** For a given `(tenant_id, locale, module, code)` combination, exactly one message record may exist. Attempting to create a second message with the same composite key is rejected via `CreateMessages`; via `UpsertMessages` it silently updates instead.  
**Violation response:** 409 — `Localization.Conflict` (on `CreateMessages` only)

---

### Cross-field: Tenant header required on every request

**Entities involved:** All Message operations  
**Rule:** Every request must include the `X-Tenant-ID` header. Requests without this header are rejected before any business logic executes.  
**Violation response:** 400 — `Localization.MissingTenantId`

---

### Cross-field: Required message fields

**Entities involved:** Message (`code`, `message`, `module`, `locale`)  
**Rule:** On Create and Upsert, each element in the `messages` array must include non-empty values for `code`, `message`, `module`, and `locale`.  
**Violation response:** 400 — `Localization.InvalidRequest`

---

### Cross-field: UUID validity on update

**Entities involved:** UpdateMessagesRequest (`uuid`)  
**Rule:** Each `uuid` in an update request must conform to valid UUID v4 format (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). Invalid UUIDs are rejected before any database query.  
**Violation response:** 400 — `Localization.InvalidInput`

---

### Cross-field: Delete requires at least one UUID

**Entities involved:** DeleteMessagesRequest  
**Rule:** The delete endpoint requires at least one `uuid` (via `?uuid=` or `?uuids[]=`). A delete request with no UUID parameters is rejected.  
**Violation response:** 400 — `Localization.MissingUUID`

---

### Cross-field: Paginated search bypasses cache

**Entities involved:** SearchMessages, MessageCache  
**Rule:** Search requests with `limit > 0` (paginated) always bypass the cache and query the database directly. Only non-paginated requests (`limit == 0`) use the cache layer.  
**Violation response:** N/A (behavior rule; no error)

---

## Cross-Schema Rules

### Cross-schema: Audit field timestamps

**Entities involved:** Message (`createdTime`, `modifiedTime`)  
**Rule:** On creation, both `createdTime` and `modifiedTime` are set to the current server timestamp. On update or upsert, only `modifiedTime` is updated to the current timestamp; `createdTime` is immutable.  
**Violation response:** N/A (enforced by service layer)

---

### Cross-schema: Audit user tracking

**Entities involved:** Message (`createdBy`, `modifiedBy`)  
**Rule:** `createdBy` is set from the `X-User-ID` header on creation and is never overwritten. `modifiedBy` is updated on every write (create, update, upsert).  
**Violation response:** N/A (enforced by service layer)

---

## Lifecycle Rules

### Lifecycle: Create vs. Upsert conflict strategy

**Entities involved:** Message  
**Rule:**
- `POST /messages` (Create): uses `INSERT … ON CONFLICT DO NOTHING`. If any message in the batch conflicts with an existing `(tenant_id, locale, module, code)`, that row is skipped. After insert, if the affected row count is less than the request count, the service detects which codes caused conflicts and returns 409.
- `PUT /messages/upsert` (Upsert): uses `INSERT … ON CONFLICT DO UPDATE SET message=…, modifiedBy=…, modifiedTime=…`. Conflicts silently update the message text and audit fields; no error is raised.  

**Violation response:** 409 — `Localization.Conflict` (Create only); silent success (Upsert)

---

### Lifecycle: Upsert deduplicates batch before DB call

**Entities involved:** UpsertMessages, Message  
**Rule:** Before executing the upsert, the service deduplicates the request batch by `(tenant_id, locale, module, code)` key in-memory. Duplicate entries within the same request are silently dropped (last wins). This prevents PostgreSQL `SQLSTATE 21000` (cardinality violation).  
**Violation response:** N/A (deduplication is silent)

---

### Lifecycle: Cache invalidation on every write

**Entities involved:** Message, MessageCache  
**Rule:** After any successful write (Create, Update, Upsert, Delete), the service invalidates cache entries for the affected `(tenant, module, locale)` combinations. Delete invalidates the entire tenant's cache (since `module`/`locale` are not known without a prior fetch). Cache invalidation failure is logged as a warning but does not fail the HTTP response.  
**Violation response:** N/A (write succeeds even if cache invalidation fails)

---

### Lifecycle: UUID auto-generation

**Entities involved:** Message (`uuid`)  
**Rule:** If a message in a Create or Upsert request does not include a `uuid`, the service generates a new UUID v4 before persisting. UUID is always present in the response.  
**Violation response:** N/A

---

## Cross-Module Rules

### Cross-module: PubSub events are fire-and-forget

**Entities involved:** Message, PubSub (Kafka/Redis)  
**Rule:** After any successful write, the service publishes an event to the configured PubSub topic (`localization-create-message`, `localization-update-message`, `localization-upsert-message`, `localization-delete-message`). Publish failures are logged but do not block the HTTP response.  
**Violation response:** N/A (caller sees 200/201)

---

### Cross-module: Tenant migration consumer

**Entities involved:** PostgreSQL schema, tenant-migration library  
**Rule:** When `SCHEMA_SEPARATION_MODE=true`, the service subscribes to the account-migration topic and runs Flyway migrations for each new tenant. Until migration completes for a tenant, requests for that tenant will fail at the database layer.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR` (if tenant schema not initialised)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-ID` header | `Localization.MissingTenantId` |
| 400 | Malformed JSON or missing required field | `Localization.InvalidRequest` |
| 400 | Delete called with no UUID parameters | `Localization.MissingUUID` |
| 400 | Invalid UUID format in update request | `Localization.InvalidInput` |
| 404 | UUID not found for tenant on update | `Localization.NotFound` |
| 409 | Duplicate `(tenant_id, locale, module, code)` on create | `Localization.Conflict` |
| 500 | Database query error (search, create, update, upsert, delete) | `Localization.SearchFailed` / `Localization.CreateFailed` / `Localization.UpdateFailed` / `Localization.UpsertFailed` / `Localization.DeleteFailed` |
| 500 | Missing-message analysis error | `Localization.FindMissingFailed` |
| 500 | Cache bust failure | `Localization.CacheBustFailed` |
