# Business Rules — Boundary Service

## Cross-Field Rules

### BR-CF-001: Geometry type and structure alignment

**Entities involved:** Boundary (`geometry`)  
**Rule:** If `geometry` is provided, its `type` must be one of: `Point`, `LineString`, `Polygon`, `MultiPoint`, `MultiLineString`, `MultiPolygon`, or `GeometryCollection`. The `coordinates` array must match the type's structural requirements. For `Polygon` types, all rings must be closed (first coordinate equals last coordinate).  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-002: Hierarchy contains exactly one root

**Entities involved:** BoundaryHierarchyDefinition (`boundaryHierarchy`)  
**Rule:** The `boundaryHierarchy` array must contain exactly one entry where `parentBoundaryType` is null. Multiple root entries or zero root entries are invalid.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-003: Hierarchy defines no circular dependencies

**Entities involved:** BoundaryHierarchyDefinition (`boundaryHierarchy`)  
**Rule:** No `boundaryType` in the `boundaryHierarchy` array may directly or transitively reference itself through parent relationships. All `parentBoundaryType` values referenced must exist as entries in the same array.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-004: Relationship boundary type exists in hierarchy

**Entities involved:** BoundaryRelationship, BoundaryHierarchyDefinition  
**Rule:** The `boundaryType` field in a relationship must be defined as an entry in the referenced hierarchy's `boundaryHierarchy` array for the same `hierarchyType`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### BR-CF-005: Parent boundary type matches hierarchy definition

**Entities involved:** BoundaryRelationship, BoundaryHierarchyDefinition  
**Rule:** If `parent` is specified in a relationship, the parent boundary's `boundaryType` must exactly match the declared `parentBoundaryType` of the current `boundaryType` in the hierarchy definition.  
**Violation response:** 400 — `BAD_REQUEST`

---

## Cross-Schema Rules

### BR-CS-001: Relationship references existing boundary entity

**Entities involved:** BoundaryRelationship → Boundary  
**Rule:** The `code` field in a relationship must reference an existing `boundary_v1` record with matching `tenantid`. Relationships cannot be created for boundary codes that do not exist.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-002: Relationship references existing hierarchy definition

**Entities involved:** BoundaryRelationship → BoundaryHierarchyDefinition  
**Rule:** The `hierarchyType` field in a relationship must reference an existing `boundary_hierarchy_v1` record for the same `tenantid`.  
**Violation response:** 404 — `NOT_FOUND`

---

### BR-CS-003: Parent relationship record must exist

**Entities involved:** BoundaryRelationship (self-referential)  
**Rule:** If `parent` is specified, an existing relationship record must exist for that parent code in the same `hierarchyType` and `tenantid`.  
**Violation response:** 404 — `NOT_FOUND`

---

## Lifecycle Rules

### BR-LC-001: Boundary code uniqueness per tenant

**Entities involved:** Boundary  
**Rule:** Each `code` must be unique within a tenant. Creating a boundary with a duplicate `code` for the same `tenantid` is rejected.  
**Violation response:** 409 — `CONFLICT`

---

### BR-LC-002: Hierarchy type uniqueness per tenant

**Entities involved:** BoundaryHierarchyDefinition  
**Rule:** Each `hierarchyType` must be unique within a tenant. Creating a hierarchy with a duplicate `hierarchyType` for the same `tenantid` is rejected.  
**Violation response:** 409 — `CONFLICT`

---

### BR-LC-003: Relationship key uniqueness per tenant

**Entities involved:** BoundaryRelationship  
**Rule:** Each combination of `(code, hierarchyType)` must be unique within a tenant. A boundary code can participate in at most one relationship per hierarchy type per tenant.  
**Violation response:** 409 — `CONFLICT`

---

## Cross-Module Rules

### BR-CM-001: Tenant isolation across all operations

**Entities involved:** Boundary, BoundaryHierarchyDefinition, BoundaryRelationship  
**Rule:** All queries are scoped to `tenantid` from the `X-Tenant-ID` header. Cross-tenant data access is prevented implicitly.  
**Violation response:** 400 — `BAD_REQUEST` (missing header); implicit isolation otherwise

---

### BR-CM-002: PubSub publish is fire-and-forget

**Entities involved:** Boundary, BoundaryRelationship, PubSub  
**Rule:** After create or update, the service publishes an event to the configured PubSub topic. If the PubSub backend is unavailable, the operation still succeeds and the event is silently dropped.  
**Violation response:** N/A (caller sees 200/201)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-ID` header | `BAD_REQUEST` |
| 400 | Invalid geometry (type unknown, unclosed polygon ring, wrong coordinate structure) | `BAD_REQUEST` |
| 400 | Hierarchy has multiple roots or zero roots | `BAD_REQUEST` |
| 400 | Hierarchy `parentBoundaryType` not found in same array | `BAD_REQUEST` |
| 400 | Circular dependency detected in hierarchy | `BAD_REQUEST` |
| 400 | `boundaryType` not in hierarchy definition | `BAD_REQUEST` |
| 400 | Parent `boundaryType` order mismatch | `BAD_REQUEST` |
| 404 | Boundary code not found for tenant | `NOT_FOUND` |
| 404 | Hierarchy type not found for tenant | `NOT_FOUND` |
| 404 | Parent relationship record not found | `NOT_FOUND` |
| 409 | Duplicate boundary code per tenant | `CONFLICT` |
| 409 | Duplicate hierarchy type per tenant | `CONFLICT` |
| 409 | Duplicate relationship (code + hierarchyType) per tenant | `CONFLICT` |
| 500 | Database or internal error | `INTERNAL_SERVER_ERROR` |
