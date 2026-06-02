# Business Rules — URL Shortener Service

---

## Cross-Field Rules

### Cross-field: ValidTill must be in the future

**Entities involved:** UrlShortenRequest  
**Rule:** If `validTill` is provided, its value (epoch ms) must be strictly greater than the current server time at the moment the shorten request is processed.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: ValidFrom must be less than ValidTill

**Entities involved:** UrlShortenRequest  
**Rule:** If both `validFrom` and `validTill` are provided, `validFrom` must be strictly less than `validTill`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: URL format and length

**Entities involved:** UrlShortenRequest  
**Rule:** The `url` field is required, must be a valid URL (http or https), and must not exceed 8192 characters.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Validity window epoch range

**Entities involved:** UrlShortenRequest  
**Rule:** `validFrom` and `validTill`, when provided, must each be ≥ 0 and ≤ 9007199254740991 (JavaScript's max safe integer).  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: ShortKeyLength range

**Entities involved:** UrlConfigRequest  
**Rule:** `shortKeyLength` is required and must be between 4 and 12 (inclusive). `maxShortKeyRetries`, when provided, must be between 1 and 20 (inclusive).  
**Violation response:** 400 — `VALIDATION_ERROR`

---

## Cross-Schema Rules

### Cross-schema: Config must exist before shortening

**Entities involved:** UrlConfig, UrlShortener  
**Rule:** A `UrlConfig` row must exist for the tenant before any URL can be shortened. The shorten operation reads `shortKeyLength` and `maxShortKeyRetries` from the config; if no config row exists for the tenant, the request is rejected.  
**Violation response:** 404 — `NOT_FOUND` ("config not found")

---

### Cross-schema: One config per tenant

**Entities involved:** UrlConfig  
**Rule:** Only one `UrlConfig` may exist per tenant. Attempting to create a second config for the same tenant is rejected.  
**Violation response:** 409 — `CONFLICT`

---

### Cross-schema: Per-tenant short-key uniqueness

**Entities involved:** UrlShortener  
**Rule:** The combination of `(tenant_id, short_key)` must be unique. The service retries key generation up to `maxShortKeyRetries` times to find a non-colliding key; if all retries produce collisions, the request fails.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR` ("key generation failed")

---

### Cross-schema: Config deletion does not cascade to URLs

**Entities involved:** UrlConfig, UrlShortener  
**Rule:** Deleting a tenant's `UrlConfig` does not delete existing `UrlShortener` rows for that tenant. However, after deletion, new URLs cannot be shortened until a new config is created.  
**Violation response:** N/A (no cascade error; subsequent shorten attempts return 404 config not found)

---

## Lifecycle Rules

### Lifecycle: Key length applies to future keys only

**Entities involved:** UrlConfig, UrlShortener  
**Rule:** Updating `shortKeyLength` in a tenant's config affects only keys generated after the update. Existing short URLs are not re-generated or invalidated.  
**Violation response:** N/A

---

### Lifecycle: Config is hard-deleted

**Entities involved:** UrlConfig  
**Rule:** Deleting a config removes it permanently from the database. There is no soft-delete or versioning; the config must be re-created from scratch if needed again.  
**Violation response:** 404 — `NOT_FOUND` (on subsequent GET/PUT/DELETE of the deleted config)

---

### Lifecycle: Validity window evaluated at redirect time

**Entities involved:** UrlShortener  
**Rule:** `validFrom` and `validTill` are not enforced at shorten time (except `validTill` must be future); they are evaluated on every redirect request. A URL becomes `URL_NOT_YET_ACTIVE` if `now < validFrom`, and `URL_EXPIRED` if `now > validTill`.  
**Violation response:** 400 — `URL_NOT_YET_ACTIVE` or `URL_EXPIRED`

---

### Lifecycle: Only permanent URLs are cached

**Entities involved:** UrlShortener, Cache  
**Rule:** A shortened URL is stored in cache only if both `validFrom` and `validTill` are absent (i.e., the URL has no expiry window). URLs with any validity constraint are never cached to prevent serving stale or expired content.  
**Violation response:** N/A (no error; URL simply bypasses cache)

---

## Cross-Module Rules

### Cross-module: Cache is a transparent read-through

**Entities involved:** UrlShortener, Cache  
**Rule:** On redirect, the service first checks the cache; a miss falls through to the database without any error. If the cache backend is unavailable, the service falls back to the database for every request.  
**Violation response:** N/A (no error exposed to caller)

---

### Cross-module: PubSub publish is fire-and-forget

**Entities involved:** UrlShortener, UrlConfig, PubSub  
**Rule:** Events published after create/update/delete operations are best-effort. If the PubSub backend is unavailable, the operation is still considered successful and no error is returned to the caller.  
**Violation response:** N/A (logged internally; caller sees 200/201)

---

### Cross-module: SERVER_HOST_NAME validated at startup

**Entities involved:** Service configuration  
**Rule:** The `SERVER_HOST_NAME` environment variable must be a valid `http://` or `https://` URL. If it is missing or malformed, the service refuses to start.  
**Violation response:** Fatal startup error (service does not bind)

---

### Cross-module: Tenant migration consumer

**Entities involved:** TenantMigration, UrlConfig, UrlShortener  
**Rule:** When schema-separation mode is enabled, the service subscribes to the tenant migration topic and runs database migrations for new tenants. If the migration consumer fails to process a message, the tenant's schema may be incomplete and all subsequent requests for that tenant will fail at the database layer.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-ID` header | `MISSING_HEADER` |
| 400 | Invalid request body (binding/validation) | `VALIDATION_ERROR` |
| 400 | `validTill` is in the past | `BAD_REQUEST` |
| 400 | `validFrom` ≥ `validTill` | `BAD_REQUEST` |
| 400 | URL not yet active (`now < validFrom` at redirect) | `URL_NOT_YET_ACTIVE` |
| 400 | URL expired (`now > validTill` at redirect) | `URL_EXPIRED` |
| 404 | No config found for tenant when shortening | `NOT_FOUND` |
| 404 | Short key not found in database on redirect | `NOT_FOUND` |
| 409 | Config already exists for tenant (POST /config when config present) | `CONFLICT` |
| 500 | All key-generation retries produced collisions | `INTERNAL_SERVER_ERROR` |
| 500 | Database or internal error | `INTERNAL_SERVER_ERROR` |
