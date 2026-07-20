package models

// BoundaryRef pairs a boundary code with the hierarchyType and boundaryType
// it belongs to, so jurisdiction validation can be scoped to the right slice
// of the boundary tree. Shared value object: stored in the jsonb column of
// the Jurisdiction entity and also returned in DTOs.
type BoundaryRef struct {
	Code          string `json:"code" binding:"required"`
	BoundaryType  string `json:"boundaryType" binding:"required"`
	HierarchyType string `json:"hierarchyType" binding:"required"`
}

// CreateJurisdictionRequest represents the request payload for creating a jurisdiction.
// employeeId is supplied via the URL path (nested resource) and is therefore
// absent from the body.
type CreateJurisdictionRequest struct {
	BoundaryRelation []BoundaryRef `json:"boundaryRelation" binding:"required,min=1,dive"`
	IsActive         *bool         `json:"isActive"`
}

// UpdateJurisdictionRequest is the PUT body for updating a jurisdiction.
// employeeId is supplied via the URL path and is therefore absent from the
// body — a jurisdiction cannot be reassigned to a different employee.
//
// boundaryRelation is required and fully replaces the existing array.
// isActive is optional: present → applied; absent → existing value preserved.
// Since this is the only mutation endpoint for a jurisdiction's active flag
// (no dedicated /deactivate or /reactivate), preserving on omission is
// a deliberate ergonomic call so callers don't have to GET-then-PUT just to
// change boundary relations.
type UpdateJurisdictionRequest struct {
	BoundaryRelation []BoundaryRef `json:"boundaryRelation" binding:"required,min=1,dive"`
	IsActive         *bool         `json:"isActive,omitempty"`
	// Version is the optimistic-concurrency token the client last read for this
	// jurisdiction. Required (min=1 rejects 0); the update compare-and-swaps on
	// it → 409 ROW_VERSION_MISMATCH on staleness. Independent of the owning
	// employee's version.
	Version int `json:"version" binding:"required,min=1"`
}

// JurisdictionResponse represents the response payload for jurisdiction operations.
// tenantId is intentionally omitted — the caller already knows their tenant from
// the X-Tenant-Id header they sent.
type JurisdictionResponse struct {
	ID               string        `json:"id"`
	EmployeeID       string        `json:"employeeId"`
	BoundaryRelation []BoundaryRef `json:"boundaryRelation"`
	IsActive         bool          `json:"isActive"`
	Version          int           `json:"version"`
	AuditDetail      AuditDetail   `json:"auditDetail,omitempty"`
}

// JurisdictionSearchCriteria carries client-supplied search filters for jurisdictions.
// Sort order is fixed server-side (createdTime DESC); tenant scoping is
// resolved from ctx in the service/repo layer, not from this struct.
//
// employeeID is intentionally absent — jurisdictions are a nested resource
// under /employees/:id/jurisdictions, so the employee scope is supplied
// positionally to the service/repo, never as a query filter on this struct.
type JurisdictionSearchCriteria struct {
	// ids are validated as UUIDs at bind time so a malformed value returns a
	// clean 400 instead of reaching the `id IN (...)` predicate on a uuid
	// column and surfacing as a Postgres 22P02 -> 500. Mirrors employee search.
	IDs      []string `form:"ids" binding:"omitempty,dive,uuid"`
	IsActive *bool    `form:"isActive"`
	// Limit/Offset bounds mirror EmployeeSearchCriteria: min=1 stops limit=0
	// from skipping the LIMIT clause (which would return all rows); max=100
	// bounds the scan; offset must be non-negative.
	Limit  int `form:"limit,default=10" binding:"min=1,max=100"`
	Offset int `form:"offset,default=0" binding:"min=0,max=2147483647"`
}
