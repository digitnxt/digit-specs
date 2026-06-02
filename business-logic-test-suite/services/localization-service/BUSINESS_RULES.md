# Business Rules — Localization Service

## Cross-Field Rules

### BR-CF-001: Composite key uniqueness enforced

**Entities involved:** localisation table (`tenant_id`, `locale`, `module`, `code`)  
**Rule:** The combination of `(tenant_id, locale, module, code)` must be unique. `CreateMessages` rejects duplicates with 409; `UpsertMessages` silently updates the message text on conflict.  
**Violation response:** 409 — `Localization.Conflict`

---

### BR-CF-002: X-Tenant-ID header mandatory on every request

**Entities involved:** All API endpoints  
**Rule:** Every API request must include the `X-Tenant-ID` header; its absence returns a 400 error before any business logic executes.  
**Violation response:** 400 — `Localization.MissingTenantId`

---

### BR-CF-003: Required fields on create and upsert

**Entities involved:** CreateMessagesRequest, UpsertMessagesRequest  
**Rule:** Each message in `Create` / `Upsert` requests must supply non-empty values for `code`, `message`, `module`, and `locale`.  
**Violation response:** 400 — `Localization.InvalidRequest`

---

### BR-CF-004: UUID format validity on update

**Entities involved:** UpdateMessagesRequest (`uuid`)  
**Rule:** UUIDs supplied in update requests must conform to valid UUID v4 format (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). Malformed UUIDs are rejected before any DB query.  
**Violation response:** 400 — `Localization.InvalidInput`

---

### BR-CF-005: Delete requires at least one UUID

**Entities involved:** DeleteMessagesRequest  
**Rule:** The delete endpoint requires at least one `uuid` (via `?uuid=` or `?uuids[]=`). A delete request with no UUID parameters is rejected.  
**Violation response:** 400 — `Localization.MissingUUID`

---

### BR-CF-006: Paginated search bypasses cache

**Entities involved:** SearchMessages, Cache  
**Rule:** Requests with `limit > 0` (paginated) bypass the cache entirely and query the database directly. Only non-paginated requests (`limit == 0`) are served from or populate the cache.  
**Violation response:** N/A (operational behavior; no error)

---

## Cross-Schema Rules

### BR-CS-001: Audit timestamps set once on create

**Entities involved:** localisation (`createdTime`, `createdBy`, `modifiedTime`, `modifiedBy`)  
**Rule:** On creation, both `createdTime` and `modifiedTime` are set to the current timestamp. `createdBy` is set from `X-User-ID` once and never overwritten. `modifiedBy` and `modifiedTime` are updated on every subsequent write.  
**Violation response:** N/A (enforced by service layer)

---

### BR-CS-002: Tenant isolation at repository level

**Entities involved:** All queries against localisation table  
**Rule:** Every query must filter by `tenant_id`; no cross-tenant data access is possible. Tenant isolation is enforced at the repository level on every operation.  
**Violation response:** N/A (architectural enforcement; no direct error)

---

## Lifecycle Rules

### BR-LC-001: Create uses DO NOTHING upsert uses DO UPDATE

**Entities involved:** CreateMessages, UpsertMessages  
**Rule:** `CreateMessages` uses `INSERT … ON CONFLICT DO NOTHING` and raises 409 if any message already exists on the composite key. `UpsertMessages` uses `INSERT … ON CONFLICT DO UPDATE SET message=…, modifiedBy=…, modifiedTime=…` and silently updates on conflict without error.  
**Violation response:** 409 — `Localization.Conflict` (Create only); silent success (Upsert)

---

### BR-LC-002: Upsert deduplicates batch before DB call

**Entities involved:** UpsertMessages  
**Rule:** Before executing the upsert, the service deduplicates the request batch by `(tenant_id, locale, module, code)` in-memory. Duplicate entries within the same request are silently dropped (last wins). This prevents PostgreSQL `SQLSTATE 21000` cardinality violations.  
**Violation response:** N/A (deduplication is silent)

---

### BR-LC-003: Cache invalidated on every write

**Entities involved:** Message, Cache  
**Rule:** Any successful write (Create, Update, Upsert, Delete) invalidates the affected cache entries for `(tenant, module, locale)`. Delete invalidates the entire tenant's cache (since `module`/`locale` are not fetched before deletion). Cache invalidation failure is logged as warning but does not fail the HTTP response.  
**Violation response:** N/A (write succeeds even if cache invalidation fails)

---

### BR-LC-004: UUID auto-generated when absent

**Entities involved:** Message (`uuid`)  
**Rule:** If a message in a Create or Upsert request omits `uuid`, the service generates a new UUID v4 before persisting. UUID is always present in the response.  
**Violation response:** N/A

---

### BR-LC-005: Update returns 404 for absent UUID

**Entities involved:** UpdateMessagesRequest  
**Rule:** If an update request references a UUID that does not exist for the given tenant, a 404 error is returned.  
**Violation response:** 404 — `Localization.NotFound`

---

## Cross-Module Rules

### BR-CM-001: PubSub events are fire-and-forget

**Entities involved:** Message, PubSub (Kafka/Redis)  
**Rule:** After any successful write, the service publishes an event to the configured PubSub topic. Publish failures are logged but do not block the HTTP response.  
**Violation response:** N/A (caller sees 200/201)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-ID` header | `Localization.MissingTenantId` |
| 400 | Missing or empty required field (`code`, `message`, `module`, `locale`) | `Localization.InvalidRequest` |
| 400 | Delete called with no UUID parameters | `Localization.MissingUUID` |
| 400 | Invalid UUID format in update request | `Localization.InvalidInput` |
| 404 | UUID not found for tenant on update | `Localization.NotFound` |
| 409 | Duplicate `(tenant_id, locale, module, code)` on Create | `Localization.Conflict` |
| 500 | Database query error (search, create, update, upsert, delete) | `Localization.SearchFailed` / `Localization.CreateFailed` / `Localization.UpdateFailed` / `Localization.UpsertFailed` / `Localization.DeleteFailed` |
| 500 | Missing-message analysis error | `Localization.FindMissingFailed` |
| 500 | Cache bust failure | `Localization.CacheBustFailed` |
