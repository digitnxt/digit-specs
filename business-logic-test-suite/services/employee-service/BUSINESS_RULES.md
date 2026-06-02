# Business Rules — Employee Service

---

## Cross-Field Rules

### Cross-field: Required employee fields

**Entities involved:** Employee (`employeeType`, `department`, `designation`)  
**Rule:** `employeeType`, `department`, and `designation` are all required and non-empty on create and full update (PUT). Partial update (PATCH) may omit them.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: EmployeeType must be a valid enum value

**Entities involved:** Employee (`employeeType`)  
**Rule:** `employeeType` must be one of: `PERMANENT`, `CONTRACT`, `TEMPORARY`.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

### Cross-field: Employee code uniqueness per tenant

**Entities involved:** Employee (`code`, `tenant_id`)  
**Rule:** `code` must be unique within a tenant. Two employees in the same tenant cannot share a code. If `code` is not provided on create, the service generates one via IDGen.  
**Violation response:** 409 — `EMPLOYEE_CODE_EXISTS`

---

### Cross-field: boundaryRelation requires all three sub-fields

**Entities involved:** Jurisdiction (`boundaryRelation`)  
**Rule:** `boundaryRelation` must contain at least one element. Each element must specify all three fields: `code`, `boundaryType`, and `hierarchyType`. Missing any field in an element is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR`

---

## Cross-Schema Rules

### Cross-schema: Tenant isolation via schema separation

**Entities involved:** Employee, Jurisdiction  
**Rule:** All queries are scoped to `tenant_id` from the `X-Tenant-ID` header via `SET search_path = tenant_schema`. Employees and jurisdictions from one tenant cannot be accessed by another.  
**Violation response:** Implicit (queries return empty for other tenants); 500 if tenant header missing

---

### Cross-schema: Jurisdiction requires valid employee in same tenant

**Entities involved:** Jurisdiction, Employee  
**Rule:** When creating or updating a jurisdiction, the referenced `employeeId` must exist in the same tenant. Jurisdictions cannot be created for employees that do not exist.  
**Violation response:** 400 — `VALIDATION_ERROR` ("employee not found")

---

### Cross-schema: Boundary validation via Boundary service

**Entities involved:** Jurisdiction (`boundaryRelation`), Boundary service  
**Rule:** When `BOUNDARY_ENABLED = true`, each `(code, boundaryType, hierarchyType)` triple in `boundaryRelation` must be validated against the Boundary service's SearchRelationship API. Invalid or non-existent combinations are rejected.  
**Violation response:** 400 — `VALIDATION_ERROR` ("invalid boundary relations")

---

## Lifecycle Rules

### Lifecycle: Soft deactivation preserves record

**Entities involved:** Employee (`isActive`)  
**Rule:** `POST /employees/:id/deactivate` sets `isActive = false`. The employee record is retained in the database for audit and historical purposes. Deactivated employees can be reactivated.  
**Violation response:** 404 — `NOT_FOUND` if employee not found

---

### Lifecycle: Hard delete cascades to jurisdictions

**Entities involved:** Employee, Jurisdiction  
**Rule:** `DELETE /employees/:id` permanently removes the employee record. All jurisdiction rows associated with that employee are automatically deleted via the `ON DELETE CASCADE` foreign key constraint.  
**Violation response:** 404 — `NOT_FOUND` if employee not found

---

### Lifecycle: Full update (PUT) replaces jurisdictions

**Entities involved:** Employee, Jurisdiction  
**Rule:** `PUT /employees/:id` deletes all existing jurisdictions for the employee and inserts the new set from the request. There is no partial merge of jurisdictions — the set is fully replaced.  
**Violation response:** 404 — `NOT_FOUND` if employee not found; 400 if jurisdiction data invalid

---

## Cross-Module Rules

### Cross-module: IDGen required for code generation

**Entities involved:** Employee (`code`), IDGen service  
**Rule:** If an employee is created without a `code`, the service calls IDGen to generate a unique code. If IDGen fails, the create operation fails.  
**Violation response:** 500 — `ID_GENERATION_ERROR`

---

### Cross-module: Individual ID validation (optional)

**Entities involved:** Employee (`individualId`), Individual service  
**Rule:** When `INDIVIDUAL_ENABLED = true`, the `individualId` provided on create or update must exist in the Individual service. If the individual is not found, the operation is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR` ("individual not found")

---

### Cross-module: Keycloak user ID validation (optional)

**Entities involved:** Employee (`userId`), Keycloak  
**Rule:** When `KEYCLOAK_ENABLED = true`, the `userId` provided on create or update must be a valid Keycloak user. If the user is not found in Keycloak, the operation is rejected.  
**Violation response:** 400 — `VALIDATION_ERROR` ("user not found in Keycloak")

---

### Cross-module: PubSub events are fire-and-forget

**Entities involved:** Employee, Jurisdiction, PubSub  
**Rule:** After each successful mutation (create, update, delete), an event is published to the configured Kafka/Redis topic. Publish failures are logged but do not block the HTTP response.  
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
