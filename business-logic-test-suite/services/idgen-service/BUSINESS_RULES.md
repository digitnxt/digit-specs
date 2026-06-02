# Business Rules — IDGen Service

---

## Cross-Field Rules

### Cross-field: Padding length must cover sequence start digits

**Entities involved:** IDGenTemplateConfig (`sequence.padding.length`, `sequence.start`)  
**Rule:** `padding.length` must be ≥ the number of digits in `sequence.start`. For example, if `start = 1000`, `padding.length` must be ≥ 4. A padding length shorter than the digit count of start renders padding meaningless and is rejected.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: Charset range order and class homogeneity

**Entities involved:** IDGenTemplateConfig (`random.charset`)  
**Rule:** In a charset range expression (e.g. `A-Z`, `0-9`), the start byte must be ≤ the end byte. Ranges that cross character classes — such as `A-1` (mixing uppercase letters and digits) — are rejected. An empty charset string is also rejected.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: Date format must match predefined keyword list

**Entities involved:** IDGenTemplateConfig, `{DATE:format}` token  
**Rule:** The format string inside a `{DATE:format}` token must exactly match one of the predefined date format keywords (e.g. `yyyymmdd`, `dd-mm-yyyy`). Free-form Go layout strings are not accepted.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: Variable tokens validated at generation time, not at template creation time

**Entities involved:** IDGenTemplateConfig (`template` string), GenerateIDRequest (`variables`)  
**Rule:** Template syntax is valid if a `{varName}` token is a non-reserved name. However, whether the required variable is present is checked at generation time: if `variables` does not contain a key matching the token name, ID generation fails.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY` (at generate time, not create time)

---

### Cross-field: Bulk count range

**Entities involved:** BulkGenerateRequest (`count`)  
**Rule:** The `count` field for bulk generation must be between 1 and 1000 (inclusive).  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Scoped reset counter resets to sequence.start, not 1

**Entities involved:** IDGenSequenceReset, IDGenTemplateConfig (`sequence.start`, `sequence.scope`)  
**Rule:** When a scoped (DAILY / MONTHLY / YEARLY) counter rolls over to a new scope window, the counter resets to `sequence.start` — not to 1. This applies consistently on every reset boundary.  
**Violation response:** N/A (enforced internally; not directly a caller error)

---

## Cross-Schema Rules

### Cross-schema: Template must exist before ID generation

**Entities involved:** IDGenTemplate, GenerateIDRequest  
**Rule:** A generate request must reference an existing `(tenantId, templateCode)` for which a template (any version) has been created. If no such template exists, generation is rejected.  
**Violation response:** 404 — `NOT_FOUND`

---

### Cross-schema: One PostgreSQL sequence per (tenant, templateCode)

**Entities involved:** IDGenTemplate, IDGenSequenceLookup  
**Rule:** Exactly one PostgreSQL sequence (named `seq_v1_<sha1(tenantId:templateCode)>`) is created when the first version of a template is created, and is shared across all versions. A second template with the same `(tenantId, templateCode)` cannot be created.  
**Violation response:** 409 — `CONFLICT`

---

### Cross-schema: Sequence and lookup are dropped only when the last version is deleted

**Entities involved:** IDGenTemplate, IDGenSequenceLookup, IDGenSequenceReset  
**Rule:** Deleting a non-last version of a template has no effect on the underlying PostgreSQL sequence, the lookup row, or any sequence-reset rows. The sequence and lookup row are dropped, and all reset rows deleted, only when the last remaining version is deleted.  
**Violation response:** N/A (enforced internally; not a caller error)

---

### Cross-schema: GLOBAL scope start is immutable after sequence creation

**Entities involved:** IDGenTemplate (`sequence.scope`, `sequence.start`), IDGenSequenceLookup  
**Rule:** Updating a template whose sequence scope is `GLOBAL` with a different `sequence.start` value is rejected. The underlying PostgreSQL sequence was already initialized with the original start value and cannot be retroactively changed without dropping and recreating it.  
**Violation response:** 422 — `UNPROCESSABLE_ENTITY`

---

### Cross-schema: Scope-reset rows are created lazily and orphaned on scope change

**Entities involved:** IDGenSequenceReset, IDGenTemplateConfig (`sequence.scope`)  
**Rule:** `idgen_sequence_resets` rows are inserted only on first use of a new scope window. If a template is updated to change scope (e.g. from `DAILY` to `MONTHLY`), old rows from the previous scope remain orphaned in the table until the last template version is deleted (at which point all reset rows for that `(tenantId, templateCode)` are deleted).  
**Violation response:** N/A (orphaned rows are harmless; no caller-visible error)

---

## Lifecycle Rules

### Lifecycle: Updates are append-only (version increment)

**Entities involved:** IDGenTemplate (`version`)  
**Rule:** Every `PUT /v3/template` inserts a new row with `version = latest + 1`. Previous version rows are never modified or deleted as a side effect of an update. The database enforces `UNIQUE(tenantid, templatecode, version)` and `CHECK(version > 0)`.  
**Violation response:** 404 — `NOT_FOUND` (if no prior version exists to update)

---

### Lifecycle: createdBy and createdTime are immutable after v1

**Entities involved:** IDGenTemplate (`createdBy`, `createdTime`)  
**Rule:** `createdBy` and `createdTime` are set only on version 1. On every subsequent version they are copied verbatim from the v1 row. `modifiedBy` and `modifiedTime` are updated on every new version with the current actor and timestamp.  
**Violation response:** Not directly user-visible; the fields are silently preserved by the service layer.

---

### Lifecycle: Delete targets a specific version only

**Entities involved:** IDGenTemplate  
**Rule:** `DELETE /v3/template?templateCode={code}&version={vN}` deletes exactly the row matching `(tenantId, templateCode, version)`. All other versions are unaffected unless this was the last version.  
**Violation response:** 404 — `NOT_FOUND` (if targeted version does not exist)

---

### Lifecycle: Version query parameter requires templateCode

**Entities involved:** IDGenTemplate, GET `/v3/template`  
**Rule:** On `GET /v3/template`, the `version` query parameter may not be supplied without also supplying `templateCode`.  
**Violation response:** 400 — `BAD_REQUEST`

---

## Cross-Module Rules

### Cross-module: Billing uses IDGen for bill numbers

**Entities involved:** IDGenTemplate, Billing service  
**Rule:** The Billing service calls `POST /v3/generate` with `templateCode = ${IDGEN_BILL_NUMBER_TEMPLATE_CODE}` during bill generation. If this IDGen call fails (service unreachable, template not found, or generation error), the entire bill generation is rolled back in Billing.  
**Violation response:** Billing receives 404/500 from IDGen → Billing returns 500 to its caller

---

### Cross-module: Billing uses IDGen for receipt numbers

**Entities involved:** IDGenTemplate, Billing service  
**Rule:** The Billing service calls `POST /v3/generate/bulk` (one ID per bill in the payment) during payment creation using `templateCode = ${IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE}`. If IDGen is unreachable, the entire payment transaction in Billing is rolled back.  
**Violation response:** Billing receives 404/500 from IDGen → Billing returns 500 to its caller

---

### Cross-module: IDGen templates must be pre-configured before Billing is used

**Entities involved:** IDGenTemplate, Billing service  
**Rule:** The Billing service expects the templates identified by `${IDGEN_BILL_NUMBER_TEMPLATE_CODE}` and `${IDGEN_RECEIPT_NUMBER_TEMPLATE_CODE}` to already exist in IDGen. If they are absent, Billing cannot generate bills or process payments. These templates must be seeded in IDGen before Billing goes live.  
**Violation response:** Billing returns 500 — `INTERNAL_SERVER_ERROR` (generation failed / template not found)

---

### Cross-module: PubSub publish is fire-and-forget

**Entities involved:** IDGenTemplate, PubSub  
**Rule:** Template CREATE / UPDATE / DELETE operations publish events to their respective topics. If the PubSub backend is unavailable, the template operation still succeeds and the event is silently dropped (logged only).  
**Violation response:** N/A (caller sees 200/201; failure is logged internally)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Charset range invalid (`A-1`, reversed range, empty charset) | `BAD_REQUEST` |
| 400 | Padding length shorter than digit count of `sequence.start` | `BAD_REQUEST` |
| 400 | Date format token uses unrecognised format string | `BAD_REQUEST` |
| 400 | `version` query param supplied without `templateCode` on GET | `BAD_REQUEST` |
| 400 | Field validation failure (bulk `count` out of range, missing required field) | `VALIDATION_ERROR` |
| 400 | Missing required header (`X-Tenant-ID` or `X-User-ID`) | `MISSING_HEADER` |
| 404 | Template or specific version not found | `NOT_FOUND` |
| 409 | Template with same `templateCode` already exists for tenant | `CONFLICT` |
| 422 | GLOBAL scope `sequence.start` changed on update | `UNPROCESSABLE_ENTITY` |
| 422 | Variable missing from `variables` map at generation time | `UNPROCESSABLE_ENTITY` |
| 422 | ID generation runtime error | `UNPROCESSABLE_ENTITY` |
| 500 | Database error or PostgreSQL sequence creation failure | `INTERNAL_SERVER_ERROR` |
