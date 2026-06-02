# Business Rules — Notification Service

---

## Cross-Field Rules

### Cross-field: Subject forbidden for SMS templates

**Entities involved:** NotificationTemplate (`subject`, `type`)  
**Rule:** `subject` is only meaningful when `type = EMAIL`. For `type = SMS`, `subject` must be absent or empty; a non-empty subject on an SMS template is rejected at create/update time.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: isHTML only valid for EMAIL templates

**Entities involved:** NotificationTemplate (`isHTML`, `type`)  
**Rule:** `isHTML` must be `false` (or omitted) when `type = SMS`. SMS providers do not render HTML. Setting `isHTML = true` on an SMS template is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Content is always required

**Entities involved:** NotificationTemplate (`content`)  
**Rule:** `content` must be non-empty for both `EMAIL` and `SMS` templates, regardless of `isHTML`, and must not exceed 102,400 characters.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Template syntax must be valid Go template at create/update time

**Entities involved:** NotificationTemplate (`content`, `subject`)  
**Rule:** Both `content` and `subject` (for EMAIL) must parse as valid Go template syntax. Validation is enforced before the row is stored; invalid syntax is never persisted.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### Cross-field: emailIds cardinality and format

**Entities involved:** EmailRequest (`emailIds`)  
**Rule:** `emailIds` must contain 1–50 addresses; each address must be a valid email format. Fewer than 1 or more than 50 addresses, or any malformed address, is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Attachments cardinality

**Entities involved:** EmailRequest (`attachments`)  
**Rule:** `attachments` may contain 0–5 filestore IDs. Supplying more than 5 IDs is rejected at the validation layer before any filestore download is attempted.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Version format and templateId dependency

**Entities involved:** EmailRequest, SMSRequest, TemplatePreviewRequest (`version`, `templateId`)  
**Rule:** `version` is optional; when supplied it must match the format `v{N}` (e.g. `v1`, `v2`). On `GET /v3/template`, a `version` query parameter may not be supplied without `templateId`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: mobileNumbers cardinality and E.164 format

**Entities involved:** SMSRequest (`mobileNumbers`)  
**Rule:** `mobileNumbers` must contain 1–10 numbers; each must match E.164 format `+[1-9][0-9]{6,14}`.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: SMS category is required

**Entities involved:** SMSRequest (`category`)  
**Rule:** `category` must be one of `OTP`, `TRANSACTION`, `PROMOTION`, `NOTIFICATION`, or `OTHERS`. It is required for DLT compliance.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

## Cross-Schema Rules

### Cross-schema: Template must exist before send

**Entities involved:** NotificationTemplate, EmailRequest / SMSRequest  
**Rule:** A send request must reference an existing `(tenantId, templateId, version)` combination. If the template or requested version is not found, the send is rejected.  
**Violation response:** 404 — `NOT_FOUND`

---

### Cross-schema: Template type gates which send endpoint can use it

**Entities involved:** NotificationTemplate (`type`), EmailRequest, SMSRequest  
**Rule:** `POST /v3/email/send` verifies the resolved template has `type = EMAIL`; `POST /v3/sms/send` verifies `type = SMS`. Using an EMAIL template for SMS dispatch (or vice versa) is rejected at the service layer.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (`ErrInvalidTemplateType`)

---

### Cross-schema: Template conflict on create

**Entities involved:** NotificationTemplate  
**Rule:** `POST /v3/template` checks whether any version of `(tenantId, templateId)` already exists. If a prior version exists, creation is rejected; callers must use `PUT /v3/template` to add a new version.  
**Violation response:** 409 — `CONFLICT`

---

### Cross-schema: isHTML flag governs rendering engine at send/preview time

**Entities involved:** NotificationTemplate (`isHTML`), rendering engine  
**Rule:** The rendering engine is selected based on the stored template's `isHTML` flag, not any field in the send request. `isHTML = true` uses `html/template` (XSS-safe auto-escaping); `isHTML = false` uses `text/template`. Subject is always rendered with `text/template` regardless of `isHTML`.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (`ErrRenderFailed`) if rendering fails

---

## Lifecycle Rules

### Lifecycle: Updates are append-only (version increment)

**Entities involved:** NotificationTemplate (`version`)  
**Rule:** Every `PUT /v3/template` inserts a new row with `version = latest + 1`. Previous version rows are never modified or deleted as a side effect of an update. The database enforces `UNIQUE(tenantid, templateid, version)` and `CHECK(version > 0)`.  
**Violation response:** 404 — `NOT_FOUND` (if no prior version exists to update)

---

### Lifecycle: createdBy and createdTime are immutable after v1

**Entities involved:** NotificationTemplate (`createdBy`, `createdTime`)  
**Rule:** `createdBy` and `createdTime` are set only on version 1. On every subsequent version they are copied verbatim from the v1 row. `modifiedBy` and `modifiedTime` are updated on every new version.  
**Violation response:** Not directly user-visible; the fields are silently preserved by the service layer.

---

### Lifecycle: Delete targets a specific version only

**Entities involved:** NotificationTemplate  
**Rule:** `DELETE /v3/template?templateId={id}&version={vN}` deletes exactly the row matching `(tenantId, templateId, version)`. All other version rows for the same `templateId` are unaffected.  
**Violation response:** 404 — `NOT_FOUND` (if the targeted version does not exist)

---

### Lifecycle: Version omission resolves to latest

**Entities involved:** NotificationTemplate, send/preview requests  
**Rule:** When `version` is omitted in a send or preview request, the service fetches the row with the highest version for `(tenantId, templateId)`.  
**Violation response:** 404 — `NOT_FOUND` (if no versions exist for the templateId under that tenant)

---

## Cross-Module Rules

### Cross-module: Enrichment via Template Config service

**Entities involved:** EmailRequest / SMSRequest / TemplatePreviewRequest (`enrich`), Template Config service  
**Rule:** When `enrich = true`, the service POSTs `{ templateId, version, payload }` to the Template Config service. The returned data is merged with the original payload (response keys take precedence on conflicts), and the merged result is passed to the template engine. Failure — network error or non-200 response — causes a hard failure; there is no fallback to the unenriched payload.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (`ErrEnrichmentFailed`)

---

### Cross-module: Attachment download via Filestore service

**Entities involved:** EmailRequest (`attachments`), Filestore service  
**Rule:** For each filestore ID in `attachments`, the service downloads the binary from the Filestore service. Callers supply filestore IDs only (0–5); base64-encoded content is not accepted in the request body. Any download failure causes a hard failure; the email is not sent.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR` (`ErrFilestoreError`)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Invalid version format; `version` supplied without `templateId` on GET | `BAD_REQUEST` |
| 400 | Missing required header (`X-Tenant-ID` or `X-User-ID`) | `MISSING_HEADER` |
| 400 | Field validation failure (email format, E.164 violation, cardinality exceeded, subject/isHTML forbidden for SMS) | `VALIDATION_ERROR` |
| 404 | Template or specific version not found | `NOT_FOUND` |
| 409 | Template already exists (templateId conflict on POST) | `CONFLICT` |
| 422 | Template syntax invalid at create/update time | `UNPROCESSABLE_ENTITY` |
| 422 | Template type mismatch (EMAIL template used for SMS send or vice versa) | `UNPROCESSABLE_ENTITY` (`ErrInvalidTemplateType`) |
| 422 | Enrichment call to Template Config service failed | `UNPROCESSABLE_ENTITY` (`ErrEnrichmentFailed`) |
| 422 | Go template render failed (missing variable, malformed syntax at render time) | `UNPROCESSABLE_ENTITY` (`ErrRenderFailed`) |
| 500 | Filestore download failed while fetching attachment | `INTERNAL_SERVER_ERROR` (`ErrFilestoreError`) |
| 500 | SMTP delivery failure | `INTERNAL_SERVER_ERROR` (`ErrSendFailed`) |
| 500 | SMS provider delivery failure | `INTERNAL_SERVER_ERROR` (`ErrSendFailed`) |
