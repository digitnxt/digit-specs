# Individual conformance — uat-saas / tenant P2 (2026-09-29)

Target `https://uat-saas.digit.org/individuals/v3` through Kong, image `anishegov/individual:master-bf8e4d5a`.

| Layer | Result |
|---|---|
| Behavioral (response / error / stateful, incl. new `userId` filter tests) | **61 / 61** |
| Schemathesis (bounded) | before spec fix: 0 / 7 · **after: 3 / 7 (1 skipped) — all 4 failures are F1** |

## Findings

**S1 — Spec: `401` not documented on any operation (spec gap)**
Every operation sits behind Kong JWT auth and returns `401 [{"code":"JWT.NoToken",...}]` when the
token is missing, but `individual.yaml` lists no 401 → `status_code_conformance` fails on 6 ops.
**FIXED** — 401 (`[Error]` array) added to all 8 operations in `v3.0.0/individual.yaml` (+ service copy).

**S2 — Spec: `POST /individuals` 409 schema is a single `Error`, service returns `[Error]`**
`409` → `[{"code":"UNIQUE_ENTITY_ERROR","message":"name already exists for this tenant"}]`.
`PUT /individuals/{id}` 409 in the same spec is already `type: array`; POST's is `$ref: Error`.
**FIXED** — rule: every error body is an array of `Error`. All 10 single-`Error` schemas (POST 409,
GET /configs 404, every 500) converted to `type: array`. `employee.yaml` already complied (all 13 ops).

**F1 — Kong `keycloak-rbac`: unknown HTTP method → 500** (shared gateway defect, see
`../employee-service/failed.md` F1). Reproduced on `/individuals/v3/individuals` too.

## Test-suite fixes (outdated vs 401e8e3, service was correct)
- `gender` is now optional on create: `test_missing_gender_returns_400` → `test_missing_gender_is_accepted`
  (201), and `assert_individual_shape` no longer requires `gender` (still validates the enum when present).
- Added `TestUserIdFilters` (search IN-match + `/exists?userId=`, unknown id → empty).
