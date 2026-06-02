# Business Rules — URL Shortener Service

## Cross-Field Rules

### BR-CF-001: ValidTill must be future at shorten time

**Entities involved:** UrlShortenRequest (`validTill`)  
**Rule:** If `validTill` is set, it must represent a time strictly in the future at the moment of the request. A `validTill` equal to the current time or in the past is rejected.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-002: ValidFrom must be less than validTill

**Entities involved:** UrlShortenRequest (`validFrom`, `validTill`)  
**Rule:** When both `validFrom` and `validTill` are provided, `validFrom` must be strictly less than `validTill`. Equal values are rejected.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-003: URL format and size constraint

**Entities involved:** UrlShortenRequest (`url`)  
**Rule:** The `url` field must be a well-formed URL (validated by Gin's `url` tag) and must not exceed 8192 characters in length.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-004: Epoch millisecond range validation

**Entities involved:** UrlShortenRequest (`validFrom`, `validTill`)  
**Rule:** `validFrom` and `validTill` must use epoch milliseconds within the safe integer range `[0, 9007199254740991]` (JavaScript `Number.MAX_SAFE_INTEGER`).  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-005: ShortKeyLength must be within bounds

**Entities involved:** UrlConfigRequest (`shortKeyLength`)  
**Rule:** `shortKeyLength` is required and must be between 4 and 12 characters (inclusive). No default is provided; the caller must explicitly supply a value.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-006: MaxShortKeyRetries must be within bounds

**Entities involved:** UrlConfigRequest (`maxShortKeyRetries`)  
**Rule:** `maxShortKeyRetries`, when provided, must be between 1 and 20 (inclusive). If omitted, it defaults to 10.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

## Cross-Schema Rules

### BR-CS-001: Config must exist before shortening

**Entities involved:** UrlConfig, UrlShortener  
**Rule:** A URL cannot be shortened unless a `UrlConfig` row exists for the tenant. The service loads the config to determine `shortKeyLength` and `maxShortKeyRetries` before generating a short key. There is no system-wide default; config must be explicitly created per tenant.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-002: Unique config per tenant enforced

**Entities involved:** UrlConfig (`tenant_id`)  
**Rule:** Only one config per tenant is permitted. `POST /config` returns 409 if a config already exists for the tenant; `PUT /config` must be used for updates.  
**Violation response:** 409 — `CONFLICT`

---

### BR-CS-003: Tenant-scoped short key uniqueness

**Entities involved:** UrlShortener (`tenant_id`, `short_key`)  
**Rule:** The `url_shortener` table has a composite unique index on `(tenant_id, short_key)`. The service retries key generation up to `maxShortKeyRetries` times; if all retries produce collisions, the request fails.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR` (key generation failed after max retries)

---

### BR-CS-004: Config deletion does not cascade to URLs

**Entities involved:** UrlConfig, UrlShortener  
**Rule:** Deleting a config row does not cascade to existing short URLs — previously shortened URLs remain resolvable. New shortening attempts after config deletion will fail with 404 config-not-found.  
**Violation response:** 404 — `NOT_FOUND` (on subsequent shorten attempts after deletion)

---

## Lifecycle Rules

### BR-LC-001: Validity window evaluated at redirect time

**Entities involved:** UrlShortener (`validFrom`, `validTill`)  
**Rule:** `validFrom` and `validTill` are evaluated using the server clock at redirect time, not at shorten time. A URL shortened with `validTill = T` becomes inaccessible exactly at `T` — there is no grace period.  
**Violation response:** 400 — `URL_NOT_YET_ACTIVE` (if now < validFrom) or 400 — `URL_EXPIRED` (if now > validTill)

---

### BR-LC-002: Expired URL remains in database unreachable

**Entities involved:** UrlShortener  
**Rule:** Once a URL expires (now > validTill), it returns 400 on every redirect until manually deleted. No automatic cleanup occurs; the row persists in the database.  
**Violation response:** 400 — `URL_EXPIRED`

---

### BR-LC-003: Config deletion is hard and immediate

**Entities involved:** UrlConfig  
**Rule:** Deleting a config permanently removes it from the database. There is no soft-delete or versioning. The config must be re-created from scratch if needed again.  
**Violation response:** 404 — `NOT_FOUND` (on subsequent GET/PUT/DELETE of the deleted config)

---

### BR-LC-004: Only permanent URLs are cached

**Entities involved:** UrlShortener, Cache  
**Rule:** A shortened URL is stored in cache only if both `validFrom` and `validTill` are absent (no expiry window). URLs with any validity constraint are never cached, to prevent serving expired content from cache.  
**Violation response:** N/A (no error; URL bypasses cache)

---

## Cross-Module Rules

### BR-CM-001: Cache fallthrough is transparent

**Entities involved:** UrlShortener, Cache  
**Rule:** On redirect, the service checks cache first; a miss falls through to the database without error. If the cache backend is unavailable, the service falls back to the database transparently.  
**Violation response:** N/A (no error exposed to caller)

---

### BR-CM-002: PubSub publish is fire-and-forget

**Entities involved:** UrlShortener, UrlConfig, PubSub  
**Rule:** Events published after create/update/delete operations are best-effort. If the PubSub backend is unavailable, the operation is still considered successful and the event is dropped (logged).  
**Violation response:** N/A (caller sees 200/201)

---

### BR-CM-003: SERVER_HOST_NAME validated at startup

**Entities involved:** Service configuration  
**Rule:** The `SERVER_HOST_NAME` environment variable must be a valid `http://` or `https://` URL. If it is missing or malformed, the service refuses to start.  
**Violation response:** Fatal startup error (service does not bind)

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
