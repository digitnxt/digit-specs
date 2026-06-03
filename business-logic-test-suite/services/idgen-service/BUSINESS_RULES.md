# Business Rules — IDGen Service

## Cross-Field Rules

### BR-CF-001: Padding must accommodate sequence start

**Entities involved:** IDGenTemplateConfig (`sequence.start`, `sequence.padding.length`)  
**Rule:** The `sequence.start` value must not exceed the number of digits representable by `padding.length`. If `sequence.start` is 1000, `padding.length` must be ≥ 4; otherwise padding has no effect and the configuration is rejected.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-002: Charset ranges must be ordered and class-homogeneous

**Entities involved:** IDGenTemplateConfig (`random.charset`)  
**Rule:** When `random.charset` is provided and non-empty, each range expression (e.g. `A-Z`, `0-9`) must have start byte ≤ end byte. Ranges that cross character classes (e.g. `A-1` mixing uppercase letters and digits) are rejected. An empty or omitted charset is valid — the service applies the default `A-Z0-9`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-003: Date format must match keyword list

**Entities involved:** IDGenTemplateConfig, `{DATE:format}` token  
**Rule:** The `{DATE:format}` token only accepts the following predefined keywords (case-insensitive). Free-form Go layout strings are not permitted.

Basic numeric: `yyyymmdd` `ddmmyyyy` `mmddyyyy` `yymmdd` `ddmmyy` `mmddyy`  
Dash-separated: `yyyy-mm-dd` `dd-mm-yyyy` `mm-dd-yyyy` `yy-mm-dd` `dd-mm-yy`  
Slash-separated: `yyyy/mm/dd` `dd/mm/yyyy` `mm/dd/yyyy` `yy/mm/dd` `dd/mm/yy`  
Dot-separated: `yyyy.mm.dd` `dd.mm.yyyy` `mm.dd.yyyy` `yy.mm.dd` `dd.mm.yy`  
Month-Year: `mmyyyy` `mm-yyyy` `mm/yyyy` `mm.yyyy` `yyyy-mm` `yyyy/mm` `yyyy.mm`  
Year only: `yyyy` `yy`

**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-004: Template variables validated at generation time

**Entities involved:** IDGenTemplateConfig, GenerateIDRequest (`variables`)  
**Rule:** Template syntax validity is determined at creation time. Whether a required variable is present is checked at generation time — not at template creation time. A missing variable causes generation to fail.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (at generation time)

---

### BR-CF-005: Scope counter resets to sequence start

**Entities involved:** IDGenSequenceReset, IDGenTemplateConfig (`sequence.start`, `sequence.scope`)  
**Rule:** When a scoped (DAILY / MONTHLY / YEARLY) counter rolls over to a new scope window, the counter resets to `sequence.start` — not to 1. The custom start value is honoured on every reset.  
**Violation response:** N/A (enforced internally; no caller-visible error)

---

## Cross-Schema Rules

### BR-CS-001: TemplateCode must exist before ID generation

**Entities involved:** IDGenTemplate, GenerateIDRequest  
**Rule:** Both `POST /v3/generate` and `POST /v3/generate/bulk` require the `templateCode` in the request to reference an existing template for the tenant. If no template exists for that code, generation is rejected with 404.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-002: One PostgreSQL sequence per template per tenant

**Entities involved:** IDGenTemplate, IDGenSequenceLookup  
**Rule:** A single PostgreSQL sequence is created per `(tenantid, templatecode)` pair at template creation time and is shared across all versions. A sequence creation failure prevents template creation.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR`

---

### BR-CS-003: Sequence only dropped on last version delete

**Entities involved:** IDGenTemplate, IDGenSequenceLookup  
**Rule:** The PostgreSQL sequence and lookup row are only dropped when the last version of the template is deleted. Deleting a non-last version has no effect on the sequence.  
**Violation response:** N/A (deletion succeeds; sequence retained)

---

### BR-CS-004: Global scope start cannot be updated

**Entities involved:** IDGenTemplate (`sequence.scope`, `sequence.start`)  
**Rule:** If a template uses `sequence.scope = GLOBAL` and an update attempts to change `sequence.start`, the update is rejected because the PostgreSQL sequence was already initialized with the original start value; retro-changing is not supported.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CS-005: Scope reset rows deleted with last version

**Entities involved:** IDGenTemplate, IDGenSequenceReset  
**Rule:** All `idgen_sequence_resets` rows for a `(tenantid, templatecode)` pair are deleted when the last template version is deleted.  
**Violation response:** N/A (cascade; no caller-visible error)

---

## Lifecycle Rules

### BR-LC-001: Template creation enforces code uniqueness

**Entities involved:** IDGenTemplate  
**Rule:** A template with `(tenantID, templateCode)` cannot be created if one already exists for that tenant. Attempting to create a duplicate returns 409.  
**Violation response:** 409 — `CONFLICT`

---

### BR-LC-002: Updates are append-only with version increment

**Entities involved:** IDGenTemplate (`version`, `createdBy`, `createdTime`)  
**Rule:** Each `PUT /v3/template` creates a new row with `version = latest + 1`. The `createdBy` and `createdTime` from version 1 are preserved verbatim across all subsequent versions. `modifiedBy` and `modifiedTime` are updated on every new version.  
**Violation response:** 404 — `NOT_FOUND` (if no prior version exists)

---

### BR-LC-003: Delete targets specific version only

**Entities involved:** IDGenTemplate  
**Rule:** `DELETE /v3/template?templateCode={code}&version={vN}` deletes exactly the row matching `(tenantId, templateCode, version)`. All other versions are unaffected unless this is the last version.  
**Violation response:** 404 — `NOT_FOUND` (if targeted version does not exist)

---

## Cross-Module Rules

### BR-CM-001: PubSub publish is fire-and-forget

**Entities involved:** IDGenTemplate, PubSub  
**Rule:** Template CREATE, UPDATE, and DELETE operations publish events. If the PubSub backend is unavailable, the operation still succeeds and the event is silently dropped (logged).  
**Violation response:** N/A (caller sees 200/201; failure is logged internally)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Charset range invalid; reversed or cross-class range; empty charset | `BAD_REQUEST` |
| 400 | Padding length shorter than digit count of `sequence.start` | `BAD_REQUEST` |
| 400 | Date format token uses unrecognised format string | `BAD_REQUEST` |
| 400 | Field validation failure (missing required field) | `VALIDATION_ERROR` |
| 400 | Missing required header (`X-Tenant-ID` or `X-User-ID`) | `MISSING_HEADER` |
| 404 | Template or specific version not found for `templateCode` | `NOT_FOUND` |
| 404 | `templateCode` not found during `/generate` or `/generate/bulk` | `NOT_FOUND` |
| 409 | Template with same `templateCode` already exists for tenant | `CONFLICT` |
| 422 | GLOBAL scope `sequence.start` changed on update | `UNPROCESSABLE_ENTITY` |
| 422 | Variable missing from `variables` map at generation time | `UNPROCESSABLE_ENTITY` |
| 422 | ID generation runtime error | `UNPROCESSABLE_ENTITY` |
| 500 | Database error or PostgreSQL sequence creation failure | `INTERNAL_SERVER_ERROR` |
