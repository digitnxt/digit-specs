# Business Rules — Employee Service

## Cross-Field Rules

### BR-CF-001: Required core employee fields non-empty

**Entities involved:** Employee (`employeeType`, `department`, `designation`)  
**Rule:** `employeeType`, `department`, and `designation` are mandatory on creation and must be non-empty strings.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-002: Employee type must be valid enum

**Entities involved:** Employee (`employeeType`)  
**Rule:** `employeeType` must be one of: `PERMANENT`, `CONTRACT`, or `TEMPORARY`.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CF-003: Boundary relation array non-empty with complete elements

**Entities involved:** EmployeeJurisdiction (`boundaryRelation`)  
**Rule:** `boundaryRelation` array must have at least one element on creation; each element must include all three fields: `code`, `boundaryType`, and `hierarchyType`.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

## Cross-Schema Rules

### BR-CS-001: Employee code uniqueness per tenant

**Entities involved:** Employee (`code`, `tenant_id`)  
**Rule:** If `code` is provided, it must be unique within the tenant scope: no two employees in the same tenant may share the same code. If no code is provided, the service generates one via IDGen.  
**Violation response:** 409 — `EMPLOYEE_CODE_EXISTS`

---

### BR-CS-002: Jurisdiction employee reference must exist

**Entities involved:** EmployeeJurisdiction, Employee  
**Rule:** `employeeId` in a jurisdiction record must reference an existing employee in the same tenant. Jurisdictions cannot be created for employees that do not exist.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### BR-CS-003: Employee deletion cascades to jurisdictions

**Entities involved:** Employee, EmployeeJurisdiction  
**Rule:** When an employee is hard-deleted, all jurisdiction records are automatically removed via `ON DELETE CASCADE` foreign key constraint.  
**Violation response:** N/A (cascade enforced by DB)

---

## Lifecycle Rules

### BR-LC-001: Soft deactivation preserves employee record

**Entities involved:** Employee (`isActive`)  
**Rule:** `POST /employees/:id/deactivate` sets `isActive = false`; the employee record persists for audit history. Deactivated employees can be reactivated.  
**Violation response:** 404 — `NOT_FOUND` (if employee not found)

---

### BR-LC-002: Hard delete removes record and cascades

**Entities involved:** Employee, EmployeeJurisdiction  
**Rule:** `DELETE /employees/:id` permanently removes the employee record. All associated jurisdiction rows are automatically deleted via the `ON DELETE CASCADE` foreign key constraint.  
**Violation response:** 404 — `NOT_FOUND` (if employee not found)

---

### BR-LC-003: PUT replaces jurisdictions atomically

**Entities involved:** Employee, EmployeeJurisdiction  
**Rule:** Full update via `PUT /employees/:id` deletes all existing jurisdictions and inserts new ones from the request body. There is no partial merge — the jurisdiction set is fully replaced.  
**Violation response:** 404 — `NOT_FOUND` (if employee not found); 400 if jurisdiction data invalid

---

## Cross-Module Rules

### BR-CM-001: IDGen auto-generates code when omitted

**Entities involved:** Employee (`code`), IDGen service  
**Rule:** If no `code` is provided during creation, the service calls IDGen to generate a unique code. If IDGen fails, the create operation fails.  
**Violation response:** 500 — `ID_GENERATION_ERROR`

---

### BR-CM-002: Individual service validates individual ID

**Entities involved:** Employee (`individualId`), Individual service  
**Rule:** When `INDIVIDUAL_ENABLED = true`, the `individualId` provided on create or update must exist in the Individual service. If the individual is not found, the operation is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR` ("individual not found")

---

### BR-CM-003: Keycloak validates user ID

**Entities involved:** Employee (`userId`), Keycloak  
**Rule:** When `KEYCLOAK_ENABLED = true`, the `userId` provided on create or update must be a valid Keycloak user. If the user is not found, the operation is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR` ("user not found in Keycloak")

---

### BR-CM-004: Boundary service validates jurisdiction relations

**Entities involved:** EmployeeJurisdiction (`boundaryRelation`), Boundary service  
**Rule:** When `BOUNDARY_ENABLED = true`, each `(code, boundaryType, hierarchyType)` triple in `boundaryRelation` must be validated against the Boundary service's SearchRelationship API. Invalid or non-existent combinations are rejected.  
**Violation response:** 400 — `VALIDATION_ERROR` ("invalid boundary relations")

---

### BR-CM-005: PubSub events are fire-and-forget

**Entities involved:** Employee, EmployeeJurisdiction, PubSub  
**Rule:** After each successful mutation (create, update, delete), corresponding events are published to PubSub. Publish failures are logged but do not block the HTTP response.  
**Violation response:** N/A (caller sees 200/201/204)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing required field (`employeeType`, `department`, `designation`) | `VALIDATION_ERROR` |
| 400 | Invalid `employeeType` enum value | `VALIDATION_ERROR` |
| 400 | `boundaryRelation` missing required sub-field | `VALIDATION_ERROR` |
| 400 | Boundary codes invalid per Boundary service | `VALIDATION_ERROR` |
| 400 | `individualId` not found in Individual service | `VALIDATION_ERROR` |
| 400 | `userId` not found in Keycloak | `VALIDATION_ERROR` |
| 400 | Malformed JSON | `INVALID_REQUEST` |
| 404 | Employee or jurisdiction not found | `NOT_FOUND` |
| 409 | Duplicate employee code within tenant | `EMPLOYEE_CODE_EXISTS` |
| 500 | Database error | `DATABASE_ERROR` |
| 500 | IDGen code generation failure | `ID_GENERATION_ERROR` |
