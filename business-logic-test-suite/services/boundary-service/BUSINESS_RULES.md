# Business Rules — Boundary Service

---

## Cross-Field Rules

### Cross-field: GeoJSON geometry validity

**Entities involved:** Boundary (`geometry`)  
**Rule:** If `geometry` is provided, `type` must be one of the valid GeoJSON types (`Point`, `LineString`, `Polygon`, `MultiPoint`, `MultiLineString`, `MultiPolygon`, `GeometryCollection`). Coordinates must conform to the structure required by that type. For `Polygon`, every ring must be closed (first and last coordinate pair must be identical).  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: Hierarchy must have exactly one root

**Entities involved:** BoundaryHierarchyDefinition (`boundaryHierarchy` array)  
**Rule:** In the `boundaryHierarchy` array, exactly one entry must have `parentBoundaryType = null`. Multiple root entries or zero root entries are rejected.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: Hierarchy parent types must exist in the same array

**Entities involved:** BoundaryHierarchyDefinition (`boundaryHierarchy` array)  
**Rule:** Every `parentBoundaryType` value referenced in the `boundaryHierarchy` array must itself appear as a `boundaryType` in the same array. A type cannot reference a parent that is not defined in the same hierarchy definition.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-field: Hierarchy must not be acyclic

**Entities involved:** BoundaryHierarchyDefinition  
**Rule:** No `boundaryType` may reference itself directly or indirectly as a parent (no circular dependency). A type cannot appear as both ancestor and descendant of itself.  
**Violation response:** 400 — `BAD_REQUEST`

---

## Cross-Schema Rules

### Cross-schema: Relationship boundary code must reference existing Boundary

**Entities involved:** BoundaryRelationship, Boundary  
**Rule:** When creating a boundary relationship, the `code` field must reference an existing boundary entity in `boundary_v1` for the same `tenantid`. Relationships cannot be created for boundary codes that do not exist.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-schema: Relationship hierarchyType must reference existing Hierarchy

**Entities involved:** BoundaryRelationship, BoundaryHierarchyDefinition  
**Rule:** The `hierarchyType` in a relationship must reference an existing hierarchy definition in `boundary_hierarchy_v1` for the same `tenantid`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-schema: Relationship boundaryType must be defined in the hierarchy

**Entities involved:** BoundaryRelationship, BoundaryHierarchyDefinition  
**Rule:** The `boundaryType` specified in a relationship must exist in the `boundaryHierarchy` array of the referenced hierarchy definition.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-schema: Parent relationship must exist

**Entities involved:** BoundaryRelationship (self-referential)  
**Rule:** If a relationship specifies a `parent` code, that parent relationship must already exist in `boundary_relationship_v1` for the same `tenantid` and `hierarchyType`.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-schema: Parent boundaryType must be declared parent in hierarchy

**Entities involved:** BoundaryRelationship, BoundaryHierarchyDefinition  
**Rule:** If a relationship specifies a `parent`, the parent's `boundaryType` must be the declared `parentBoundaryType` of the child's `boundaryType` in the hierarchy definition.  
**Violation response:** 400 — `BAD_REQUEST`

---

### Cross-schema: Tenant isolation

**Entities involved:** Boundary, BoundaryHierarchyDefinition, BoundaryRelationship  
**Rule:** All three tables are filtered by `tenantid`. Cross-tenant queries are prevented. A boundary, hierarchy, or relationship from one tenant cannot be referenced by another tenant.  
**Violation response:** Implicit (queries return empty results for other tenants)

---

## Lifecycle Rules

### Lifecycle: Boundary code uniqueness per tenant

**Entities involved:** Boundary  
**Rule:** `(code, tenantid)` must be unique. Once a boundary code is created for a tenant, it cannot be duplicated.  
**Violation response:** 409 — `CONFLICT`

---

### Lifecycle: Hierarchy type uniqueness per tenant

**Entities involved:** BoundaryHierarchyDefinition  
**Rule:** `(tenantid, hierarchytype)` must be unique. Only one hierarchy definition per type per tenant is allowed.  
**Violation response:** 409 — `CONFLICT`

---

### Lifecycle: Relationship uniqueness per code and hierarchy

**Entities involved:** BoundaryRelationship  
**Rule:** `(tenantid, code, hierarchytype)` is the composite primary key. A boundary code may appear in multiple hierarchies but only once per hierarchy per tenant.  
**Violation response:** 409 — `CONFLICT`

---

### Lifecycle: Audit fields set on creation, modifiedBy updated on update

**Entities involved:** Boundary, BoundaryHierarchyDefinition, BoundaryRelationship  
**Rule:** On creation: `createdBy` = X-User-ID, `createdTime` = current epoch ms, `modifiedBy` = X-User-ID, `modifiedTime` = current epoch ms. On update: only `modifiedBy` and `modifiedTime` change; `createdBy` and `createdTime` are never overwritten.  
**Violation response:** N/A (enforced by service layer)

---

## Cross-Module Rules

### Cross-module: PubSub publish is fire-and-forget

**Entities involved:** Boundary, BoundaryRelationship, PubSub  
**Rule:** After create or update, the service publishes an event to the configured PubSub topic. If the PubSub backend is unavailable, the operation is still considered successful and the event is silently dropped.  
**Violation response:** N/A (logged; caller sees 200/201)

---

### Cross-module: Tenant migration consumer

**Entities involved:** Tenant migration, PostgreSQL schema  
**Rule:** When `SCHEMA_SEPARATION_MODE=true`, the service subscribes to the tenant migration topic and runs Flyway migrations for new tenants before any requests for that tenant can succeed.  
**Violation response:** 500 — `INTERNAL_SERVER_ERROR` (if tenant schema is not initialized)

---

## Error Reference

| HTTP Status | Condition | Error Code |
|---|---|---|
| 400 | Missing `X-Tenant-ID` header | `BAD_REQUEST` |
| 400 | Invalid GeoJSON geometry (invalid type, unclosed polygon ring, wrong coordinate structure) | `BAD_REQUEST` |
| 400 | Hierarchy has multiple roots or zero roots | `BAD_REQUEST` |
| 400 | Hierarchy has parent type not defined in same array | `BAD_REQUEST` |
| 400 | Circular dependency in hierarchy | `BAD_REQUEST` |
| 400 | Relationship code does not reference existing boundary | `BAD_REQUEST` |
| 400 | Relationship hierarchyType not found for tenant | `BAD_REQUEST` |
| 400 | Relationship boundaryType not in hierarchy | `BAD_REQUEST` |
| 400 | Parent relationship not found | `BAD_REQUEST` |
| 400 | Parent boundaryType order mismatch | `BAD_REQUEST` |
| 404 | Boundary / hierarchy / relationship not found | `NOT_FOUND` |
| 409 | Duplicate boundary code per tenant | `CONFLICT` |
| 409 | Duplicate hierarchy type per tenant | `CONFLICT` |
| 409 | Duplicate relationship (code + hierarchyType) per tenant | `CONFLICT` |
| 500 | Database or internal error | `INTERNAL_SERVER_ERROR` |
