# individual-go — runtime test results

Environment: docker-compose (postgres :5434, vault disabled), server on :8080, tenant `public`
(tables live in the `public` schema; the tenantdb middleware sets search_path = tenant-id).
idgen not wired (fallback `IND-<uuid>` ids — see B-analysis). DB truncated before the run.

Legend: each case = TARGET · EXPECTED · REQUEST (method/path/headers/body) · RESPONSE (status+body) · VERDICT.

---

## F1 — foundation create
- **Target:** baseline create path
- **Expected:** 201, version=1, isActive=true, individualId present
- **Request:** `POST /individuals/v3/individuals`  headers: `X-Tenant-ID: public`, `X-User-ID: tester`
  ```json
  {"givenName":"Ravi","gender":"MALE","mobileNumber":"9800000001"}
  ```
- **Response:** `201 Created`, `Location: /individuals/v3/individuals/c37c25a1-1f11-49c3-bcd5-a5f2d8bbd51a`
  ```json
  {"id":"c37c25a1-1f11-49c3-bcd5-a5f2d8bbd51a","individualId":"IND-27af06e5","givenName":"Ravi","gender":"MALE","mobileNumber":"9800000001","mobileNumberVerified":false,"emailVerified":false,"isActive":true,"version":1,"requestId":"6e37c4af-f249-499f-94ad-3f3460afb0f1","auditDetail":{"createdBy":"tester","createdTime":1784026429942,"modifiedBy":"tester","modifiedTime":1784026429942}}
  ```
- **Verdict:** PASS ✅

## F2 — create with 1 address, 1 AADHAAR, 2 documents (join-removal / children)
- **Target:** address as direct-FK child + child persistence + read
- **Expected:** create 201; GET 200 with counts address=1, identifiers=1, documents=2
- **Request (create):** `POST /individuals/v3/individuals`  headers: `X-Tenant-ID: public`, `X-User-ID: tester`
  ```json
  {"givenName":"Meena","gender":"FEMALE","mobileNumber":"9811111111","address":[{"type":"PERMANENT","city":"Pune"}],"identifiers":[{"identifierType":"AADHAAR","identifierId":"111122223333"}],"documents":[{"documentType":"PROOF_OF_RESIDENCE","fileStoreId":"fs-1"},{"documentType":"IDENTITY_PROOF","fileStoreId":"fs-2"}]}
  ```
- **Response (create):** `201`, id=`c26774cf-517b-4f17-967c-5d93b40002d5`, individualId=`IND-05428408`, version=1.
  Children — address[0].id=`78d00dca-296c-421c-9720-c7830f9289e7`; AADHAAR id=`12b4510b-f280-42e4-8c91-09c4068aa969` (individualId set, active=true); documents `89d4eef7…`(PROOF_OF_RESIDENCE, fs-1) + `4b5689bd…`(IDENTITY_PROOF, fs-2).
- **Request (verify):** `GET /individuals/v3/individuals/c26774cf-517b-4f17-967c-5d93b40002d5`
- **Response (verify):** `200`, counts **address=1 / identifiers=1 / documents=2**.
- **Verdict:** PASS ✅  (address is a direct-FK child; join table gone.)

## B7-a — GET (read) WITHOUT X-User-ID
- **Target:** B7 — reads must not require X-User-ID
- **Expected:** 200
- **Request:** `GET /individuals/v3/individuals?givenName=Meena`  headers: `X-Tenant-ID: public` (no X-User-ID)
- **Response:** `200`, `{"totalCount":1,...,"individuals":[{...Meena...}]}`
- **Verdict:** PASS ✅

## B7-b — POST (write) WITHOUT X-User-ID
- **Target:** B7 — writes still require X-User-ID
- **Expected:** 400 "Missing required header: X-User-ID"
- **Request:** `POST /individuals/v3/individuals`  headers: `X-Tenant-ID: public` (no X-User-ID)
  ```json
  {"givenName":"NoUser","gender":"MALE","mobileNumber":"9800000009"}
  ```
- **Response:** `400`
  ```json
  [{"code":"VALIDATION_ERROR","message":"Missing required header: X-User-ID"}]
  ```
- **Verdict:** PASS ✅

## B3 — PUT without version
- **Target:** B3 — version required on PUT
- **Expected:** 400 VALIDATION_ERROR "version is required for update"
- **Request:** `PUT /individuals/v3/individuals/c26774cf-517b-4f17-967c-5d93b40002d5`  headers: tenant+user
  ```json
  {"givenName":"Meena","gender":"FEMALE","mobileNumber":"9811111111"}
  ```
- **Response:** `400`
  ```json
  [{"code":"VALIDATION_ERROR","message":"Validation failed","description":"One or more validation checks failed","params":{"field":"version","message":"version is required for update"}}]
  ```
- **Verdict:** PASS ✅

## B9-a — PUT with stale version=999 (current=1)
- **Target:** B9 — optimistic-concurrency reject on stale version
- **Expected:** 409 ROW_VERSION_MISMATCH
- **Request:** `PUT /individuals/v3/individuals/c26774cf-…`  body includes `"version":999`
- **Response:** `409`
  ```json
  [{"code":"ROW_VERSION_MISMATCH","message":"Row version mismatch","description":"The entity has been modified by another user","params":{"expected":1,"provided":999}}]
  ```
- **Verdict:** PASS ✅

---

## B4 + B14 + B16 — full-replace PUT (one request, three fixes)
- **Target:** B4 omitted-child removal · B14 identifier match-by-type (in-place, stable id) · B16 new child inserted on PUT
- **Setup:** Meena `c26774cf-…`, version 1. Existing: address `78d00dca…`; AADHAAR `12b4510b…`; docs `89d4eef7…`(PROOF_OF_RESIDENCE) + `4b5689bd…`(IDENTITY_PROOF).
- **Expected:** 200; version→2; address=1 (kept); documents=[PROOF_OF_RESIDENCE] only (IDENTITY_PROOF removed); identifiers=2 → AADHAAR SAME id `12b4510b…` (in-place) + new PAN.
- **Request:** `PUT /individuals/v3/individuals/c26774cf-…`  headers: tenant+user. Address by id (keep), 1 doc by id (keep), AADHAAR **without id**, new PAN **without id**:
  ```json
  {"givenName":"Meena","gender":"FEMALE","mobileNumber":"9811111111","version":1,"address":[{"id":"78d00dca-296c-421c-9720-c7830f9289e7","type":"PERMANENT","city":"Pune"}],"identifiers":[{"identifierType":"AADHAAR","identifierId":"111122223333"},{"identifierType":"PAN","identifierId":"ABCDE1234F"}],"documents":[{"id":"89d4eef7-1272-4c63-9091-3babeb481f18","documentType":"PROOF_OF_RESIDENCE","fileStoreId":"fs-1"}]}
  ```
- **Response:** `200`, version=2. Full body:
  ```json
  {"id":"c26774cf-517b-4f17-967c-5d93b40002d5","individualId":"IND-05428408","givenName":"Meena","gender":"FEMALE","mobileNumber":"9811111111","mobileNumberVerified":false,"emailVerified":false,"isActive":true,"version":2,"requestId":"b403b7a7-…","auditDetail":{"createdBy":"tester","createdTime":1784026469045,"modifiedBy":"tester","modifiedTime":1784028018675},"address":[{"id":"78d00dca-296c-421c-9720-c7830f9289e7","type":"PERMANENT","city":"Pune",...}],"identifiers":[{"id":"12b4510b-f280-42e4-8c91-09c4068aa969","identifierType":"AADHAAR",...},{"id":"24fd5d47-91d3-4349-ad01-c34bc554d371","identifierType":"PAN",...}],"documents":[{"id":"89d4eef7-1272-4c63-9091-3babeb481f18","documentType":"PROOF_OF_RESIDENCE","fileStoreId":"fs-1",...}]}
  ```
- **Verify (GET):** version=2; address=1; documents=`["PROOF_OF_RESIDENCE"]`; identifiers=AADHAAR `12b4510b…`(unchanged, `same_id=true`) + PAN `24fd5d47…`.
- **Verdict:** PASS ✅ (B4 remove ✓, B14 in-place match-by-type ✓ no collision, B16 new child inserted ✓)

---

## B15 — PUT referencing ANOTHER individual's child id (IDOR)
- **Target:** B15 — a PUT must not reassign/modify another individual's child
- **Setup:** created Y `cc1aa528-…` with document `96056605-9070-440a-9abb-011ca7e05295` (fileStoreId `fs-y`). PUT Meena `c26774cf-…` (version 2) sending a document carrying Y's id.
- **Expected:** 400 "document id does not belong to this individual"; Y's document unchanged.
- **Request:** `PUT /individuals/v3/individuals/c26774cf-…`  headers: tenant+user
  ```json
  {"givenName":"Meena","gender":"FEMALE","mobileNumber":"9811111111","version":2,"documents":[{"id":"96056605-9070-440a-9abb-011ca7e05295","documentType":"IDENTITY_PROOF","fileStoreId":"stolen"}]}
  ```
- **Response:** `400`
  ```json
  [{"code":"VALIDATION_ERROR","message":"Validation failed","description":"One or more validation checks failed","params":{"field":"documents","message":"document id does not belong to this individual: 96056605-9070-440a-9abb-011ca7e05295"}}]
  ```
- **Verify:** `GET Y` → its document still `{id:96056605…, fileStoreId:"fs-y"}` (NOT "stolen") — no theft.
- **Verdict:** PASS ✅

---

## Validation — required fields & at-least-one (all `POST /individuals/v3/individuals`, tenant+user)
| # | Request body | Expected | Response | Verdict |
|---|---|---|---|---|
| V1 | `{"gender":"MALE","mobileNumber":"9801000001"}` | 400 field givenName | `400 [{VALIDATION_ERROR … params:{field:"givenName",message:"givenName is required"}}]` | PASS ✅ |
| V2 | `{"givenName":"Asha","mobileNumber":"9801000002"}` | 400 field gender | `400 … params:{field:"gender",message:"gender is required"}` | PASS ✅ |
| V3 | `{"givenName":"Asha","gender":"FEMALE"}` | 400 mobile/email | `400 … params:{field:"mobileNumber/email",message:"at least one of mobileNumber or email is required"}` | PASS ✅ |
| V4 | `{"givenName":"Asha","gender":"FEMALE","email":"asha@example.com"}` | 201 | `201` id=`d49519cc-…`, version=1 | PASS ✅ |

## Validation — field formats (all `POST`, tenant+user; 400 unless noted)
| # | Offending field | Expected message | Response | Verdict |
|---|---|---|---|---|
| V5 | `gender:"X"` | "gender must be MALE, FEMALE, or OTHER" | 400, field=gender ✓ | PASS ✅ |
| V6 | `email:"bad@"` | "invalid email format" | 400, field=email ✓ | PASS ✅ |
| V7 | `mobileNumber:"123"` | "mobileNumber must be 6-15 digits" | 400, field=mobileNumber ✓ | PASS ✅ |
| V8 | `givenName:"Ravi2"` (digit) | "givenName must contain only alphabets and spaces" | 400, field=givenName ✓ (baseline alphaOnly) | PASS ✅ |
| V9 | `givenName` 129×`A` | "givenName must not exceed 128 characters" | 400, field=givenName ✓ | PASS ✅ |
| V10 | `age:151` | "age must be between 0 and 150" | 400, field=age ✓ | PASS ✅ |
| V11 | `dateOfBirth:"2999-01-01"` | "dateOfBirth must not be in the future" | 400, field=dateOfBirth ✓ | PASS ✅ |
| V12 | `dateOfBirth:"1800-01-01"` | ">150 years in the past" | 400 "must not be more than 150 years in the past" ✓ | PASS ✅ |

## Validation — additionalAttributes + nested entities (all `POST`; 400)
| # | Offending input | Response (field · message) | Verdict |
|---|---|---|---|
| V13 | `additionalAttributes:{"occupation":5}` | `additionalAttributes.occupation` · "values must be strings" | PASS ✅ |
| V14 | `additionalAttributes:{"bad key":"x"}` | `additionalAttributes.bad key` · "keys must match ^[a-zA-Z0-9_.-]+$" | PASS ✅ |
| V15 | `address:[{"type":"PERMANENT"}]` | `address[0]` · "requires at least one of doorNo, street, landmark, or city" | PASS ✅ |
| V16 | `address:[{"city":"X","type":"HOME"}]` | `address[0].type` · "must be PERMANENT or CORRESPONDENCE" | PASS ✅ |
| V17 | `address:[{"city":"X","latitude":100}]` | `address[0].latitude` · "must be between -90 and 90" | PASS ✅ |
| V18 | `identifiers:[{"identifierType":"FOO","identifierId":"1"}]` | `identifiers[0].identifierType` · "invalid identifierType" | PASS ✅ |
| V19 | two AADHAAR | `identifiers` · "duplicate identifierType: AADHAAR" | PASS ✅ |
| V20 | `identifiers:[{"identifierType":"PAN"}]` | `identifiers[0].identifierId` · "identifierId is required" | PASS ✅ |
| V21 | `documents:[{"documentType":"X","fileStoreId":"fs"}]` | `documents[0].documentType` · "must be 2-64 characters" | PASS ✅ |
| V22 | `documents:[{"documentType":"PASSPORT"}]` | `documents[0].fileStoreId` · "fileStoreId is required" | PASS ✅ |

## Validation — business array caps (all `POST`; 400; count checked before content)
| # | Input | Response | Verdict |
|---|---|---|---|
| V23 | 17 addresses | `field:"address"` · "address must contain at most 16 entries" | PASS ✅ |
| V24 | 17 identifiers | `field:"identifiers"` · "identifiers must contain at most 16 entries" | PASS ✅ |
| V25 | 21 documents | `field:"documents"` · "documents must contain at most 20 entries" | PASS ✅ |

## Config precedence — Phase 1: NO config (baseline governs) [B11 + B12]
- Precheck: `GET /individuals/v3/configs` (tenant public) → `404 [{"code":"NOT_FOUND","message":"No configuration found for this tenant"}]` (confirms no config).
| # | Request | Expected | Response | Verdict |
|---|---|---|---|---|
| CP1 | create mobile `+919876500001` | 400 (baseline rejects `+`) | `400` field=mobileNumber "must be 6-15 digits" | PASS ✅ |
| CP2 | create mobile `9876500002` | 201 (baseline accepts) | `201` id=`7d20c48a-…` | PASS ✅ |
| B12-a | two creates mobile `9833333333` | both 201 (no default uniqueness) | `201` then `201` | PASS ✅ |

## Config precedence — Phase 2: config present (tenant regex REPLACES baseline) [B11]
- **Set config:** `POST /individuals/v3/configs` body `{"mobileRegex":"^\\+91[0-9]{10}$","nameRegex":"^[a-zA-Z0-9 ]+$"}` → `201`, version=1. `GET /configs` returns it.
| # | Request | Expected | Response | Verdict |
|---|---|---|---|---|
| CP3 | create mobile `+919876500003` | 201 — config **allows** what baseline forbids | `201` id=`9de8bb09-…` | PASS ✅ |
| CP4 | create mobile `9876500004` (plain) | 400 — config **rejects** what baseline allows | `400` "mobileNumber does not match the configured pattern for this tenant" | PASS ✅ |
| B11 | create `givenName:"Ravi2"` (digit), mobile `+919876500005` | 201 — nameRegex override allows digit | `201` id=`8b7414fc-…` | PASS ✅ |
- **Conclusion:** CP1/CP2 (no config ⇒ baseline) + CP3/CP4 (config ⇒ only tenant regex governs, both directions) = config-vs-baseline precedence fully verified.

## B12-b — opt-in mobile uniqueness (+ B10 config version bump)
- **Update config:** `POST /configs` add `uniquenessCriteria:["mobileNumber"]` → `200`, **version 2** (in-place counter; `createdTime` preserved, `modifiedTime` new) — B10 confirmed single-threaded.
- **Expected:** dup `+91` mobile now 409.
| # | Request | Expected | Response | Verdict |
|---|---|---|---|---|
| B12-b#1 | create mobile `+919000000001` | 201 | `201` | PASS ✅ |
| B12-b#2 | create mobile `+919000000001` (dup) | 409 UNIQUE_ENTITY | `409 [{"code":"UNIQUE_ENTITY_ERROR",…,"params":{"field":"mobileNumber","message":"mobileNumber already exists for this tenant"}}]` | PASS ✅ |

## B5 — soft-delete deactivates individual + ALL children
- **Target:** B5 — delete cascades to identifiers, documents, addresses; reads exclude deleted
- **Subject:** Meena `c26774cf-…` (version 2; 2 identifiers, 1 active doc, 1 addr).
- **Expected:** DELETE 204; GET 404; search excludes; all children active=false.
- **Responses:** `DELETE → 204`; `GET → 404 [{"code":"NOT_FOUND","message":"Individual not found"}]`; `GET /individuals?mobileNumber=9811111111 → {"totalCount":0}`.
- **DB verify:** `individual_identifier_v3` 2 rows / **0 active**; `individual_document_v3` 2 rows / **0 active**; `individual_address_v3` 1 row / **0 active**.
- **Verdict:** PASS ✅ (all children deactivated; also retro-confirms B4 — one doc was already inactive from the earlier full-replace).

## B9-conc — 5 concurrent PUTs with the same version (atomic OCC)
- **Target:** B9 — the compare-and-swap must let only ONE writer win; ≥2 wins = lost update.
- **Setup:** fresh individual `f416c4c2-…` version 1. Fired 5 concurrent `PUT … version=1`.
- **Expected:** exactly one 200, four 409; final version = 2.
- **Result:** status codes = **1× 200, 4× 409**; DB `rowversion = 2`.
- **Verdict:** PASS ✅ (CAS atomic — no lost updates)

## B10-conc — 5 concurrent config upserts (atomic counter)
- **Target:** B10 — the `SELECT … FOR UPDATE` must serialise upserts so the version counter can't under-count.
- **Setup:** config at version 2; fired 5 concurrent `POST /configs`.
- **Expected:** all 200; final version = 7 (2+5).
- **Result:** status codes = **5× 200**; DB `version = 7`.
- **Verdict:** PASS ✅ (counter integrity under concurrency — lock works)

---

## SUMMARY — all cases PASS ✅
| Group | Cases | Result |
|---|---|---|
| Foundation / join-removal | F1, F2 | PASS |
| B7 header | B7-a, B7-b | PASS |
| B3 version-required | B3 | PASS |
| B9 OCC reject | B9-a | PASS |
| B4 full-replace + B14 match-by-type + B16 new-child | (combined PUT) | PASS |
| B15 IDOR (foreign child) | B15 | PASS |
| Validation: required/at-least-one | V1–V4 | PASS |
| Validation: formats | V5–V12 | PASS |
| Validation: additionalAttributes + nested | V13–V22 | PASS |
| Validation: array caps | V23–V25 | PASS |
| B11 config-vs-baseline precedence | CP1–CP4 + B11-name | PASS |
| B12 uniqueness (default off / opt-in) | B12-a, B12-b | PASS |
| B5 soft-delete cascade | B5 | PASS |
| B9 concurrency (atomic CAS) | B9-conc (1×200/4×409) | PASS |
| B10 concurrency (atomic counter) | B10-conc (version 2→7) | PASS |

**Runtime verification complete (Go service).** All fixes behave as designed, including the two concurrency guarantees. Not yet run: Java service (same suite), and idgen integration (fallback ids used — see idgen analysis).

---

## Java service run (config mirrored from config.go)

**Setup:** Go service stopped. Java `individual.jar` launched on :8080 with env matching config.go —
DB `localhost:5434/postgres` (user `postgres`/`password`), `VAULT_ENABLED=false`,
`SCHEMA_SEPARATION_MODE=false`, `PUBSUB_ENABLED=false`, context-path `/individuals`.
Startup Flyway removed (`FlywayConfig` deleted — deploy runs migrations in an init-container);
schema already present, data truncated before the run.
**idgen wired:** k8s-deployed idgen (namespace `egov`) port-forwarded to `localhost:8100`;
`IDGEN_HOST=http://localhost:8100`, `IDGEN_PATH=/idgen/v3/generate`, format `individual`
(template `IND-{SEQ}` registered). Create returns real sequence ids (`IND-000006`, …), not fallback.

**Bug found + fixed mid-run — B18 (Java only, HIGH):** first smoke create returned address+documents
but no identifiers; DB showed the identifier row with `active=f`. Root cause: `IdentifierDTO.active`
is a primitive `boolean` (defaults false), `ModelMappers` copies it onto the entity, and
`insertIdentifier` writes the `active` column explicitly → row persisted inactive and filtered by every
read. Address/document map the same false but their INSERT omits `active` (DB default masks it). Fix:
`enrichForCreate` now sets `active=true` on every request child. Re-verified: GET returns the identifier.

**Bug suite (S1–S10): 18/18 real assertions PASS.** The lone reported "FAIL" (S8 "config upsert 201/200")
is a test-harness artifact — the assertion compares first-POST (201 create) to second-POST (200 update)
and expects them equal; 201-then-200 is the correct in-place upsert, so the service is right.
Covered: create counts (S1), GET (S2), B3 version-required 400 (S3), B9 stale-version 409 (S4),
B4+B14 full-replace + identifier match-by-type + B16 (S5), B15 foreign-child IDOR 400 (S6),
B12 default dup-mobile allowed (S7), B11 tenant nameRegex override (S8), B12 opt-in uniqueness 409 (S9),
B5 delete cascade + 404 (S10).

**Format / business-logic battery (V1–V12): all behaviors correct.** Confirmed-correct after checking
code (not all matched my first guess — logged so the reasoning is explicit):
- V1 missing givenName → 400; V3 non-digit mobile → 400; V4 10-digit mobile → 201; V6 12-digit AADHAAR → 201.
- V2 9-digit mobile → **201 (correct)**: baseline mobile pattern is `^[0-9]{6,15}$`, not exactly 10.
- V5 11-digit AADHAAR → **201 (correct)**: `validateIdentifiers` checks type-enum + presence + maxLen(64) only;
  no AADHAAR length/format check (matches the v3 spec — same in Go).
- V7 baseline rejects digit-in-name (pre-config) → 400; V8 missing X-User-ID on POST → 400.
- V9 GET / V10 PUT **absent UUID → 404**; a **malformed** (non-UUID) id → 400 "ID must be a valid UUID"
  (defensive id-format check — correct).
- V11 same AADHAAR value on two different individuals → **201 (correct)**: uniqueness is
  `(individualId, identifierType)` (one AADHAAR per individual), not a global identifier-value constraint.
- V12 tenant isolation: cross-tenant read → 404, same-tenant → 200.

**Java runtime verification complete.** Behaves identically to Go on every bug + business-rule case;
idgen integrated; one Java-only defect (B18) found and fixed during the run.

---

## Go run — validation-message + constants-extraction verification

Go server rebuilt and run on :8080 (config.go defaults: DB 5434/postgres, vault off) with idgen
wired (`IDGEN_PATH=/idgen/v3/generate`, format `individual`). DB truncated first.

**Targeted checks (all PASS):**
- **Date format fix** — `dateOfBirth:"2999-01-01"` → 400 with `params.value:"2999-01-01"` (date-only,
  no `T00:00:00Z`); `"1800-01-01"` → 400 "more than 150 years in the past", `value:"1800-01-01"`.
  Confirms the `Format(time.RFC3339)` → `Format("2006-01-02")` fix.
- **Email message** — `"bad@"` → "email must be a valid email address, e.g. name@example.com".
- **identifierType enum** — `"FOO"` → "identifierType must be one of NATIONAL_ID, AADHAAR, PASSPORT,
  VOTER_ID, PAN, DRIVING_LICENSE, SYSTEM_GENERATED".
- **Constants wired** — 129-char givenName → 400 "givenName must not exceed 128 characters"
  (proves `nameMaxLen` is referenced, not just compiled).

**Bug suite (S1–S10): 18/18 real assertions PASS** (same lone config-upsert 201-vs-200 test artifact).
No regression from the Tier-1 constants extraction. Go's error envelope carries `params.value`
(the Java `value`/envelope alignment remains the one deferred item).

