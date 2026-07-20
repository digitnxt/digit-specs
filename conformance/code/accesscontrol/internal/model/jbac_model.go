package model

import (
	"encoding/json"
)

// JbacRule represents a JBAC rule
type JbacRule struct {
	ID                    string          `json:"id" gorm:"type:uuid;primary_key"`
	TenantID              string          `json:"tenantId" gorm:"column:tenant_id"`
	Name                  string          `json:"name" gorm:"column:name"`
	PathPattern           string          `json:"pathPattern" gorm:"column:path_pattern"`
	Methods               StringArray     `json:"methods" gorm:"column:methods;type:text[]"`
	Enforcement           string          `json:"enforcement" gorm:"column:enforcement"`
	ParentImpliesChildren bool            `json:"parentImpliesChildren" gorm:"column:parent_implies_children"`
	ExtractJurisdiction   json.RawMessage `json:"extractJurisdiction,omitempty" gorm:"column:extract_jurisdiction;type:jsonb"`
	Description           string          `json:"description,omitempty" gorm:"column:description"`
	RequestID             string          `json:"requestId,omitempty"   gorm:"column:requestid"`
	AuditDetails          AuditDetail     `json:"auditDetails" gorm:"embedded"`
}

func (JbacRule) TableName() string {
	return "access_jbac_rules"
}

// CreateJbacRuleRequest represents the request to create a new JBAC rule
type CreateJbacRuleRequest struct {
	Name                  string          `json:"name"`
	PathPattern           string          `json:"pathPattern"`
	Methods               []string        `json:"methods"`
	Enforcement           string          `json:"enforcement"`
	ParentImpliesChildren bool            `json:"parentImpliesChildren"`
	ExtractJurisdiction   json.RawMessage `json:"extractJurisdiction,omitempty"`
	Description           string          `json:"description,omitempty"`
}

// UpdateJbacRuleRequest represents a PATCH-style partial update of a JBAC rule.
//
// Required fields use plain pointers: absent = don't touch, present = update.
// JSON `null` on any of these is rejected by the handler via the field list
// JbacNonNullableUpdateFields below.
//
// Optional clearable fields (extractJurisdiction, description) use Nullable[T]
// to distinguish "absent" from "explicit null = clear column".
type UpdateJbacRuleRequest struct {
	Name                  *string                   `json:"name,omitempty"`
	PathPattern           *string                   `json:"pathPattern,omitempty"`
	Methods               *StringArray              `json:"methods,omitempty"`
	Enforcement           *string                   `json:"enforcement,omitempty"`
	ParentImpliesChildren *bool                     `json:"parentImpliesChildren,omitempty"`
	ExtractJurisdiction   Nullable[json.RawMessage] `json:"extractJurisdiction,omitempty"`
	Description           Nullable[string]          `json:"description,omitempty"`
}

// JbacNonNullableUpdateFields lists the top-level fields on
// UpdateJbacRuleRequest that must not accept JSON `null`. The handler uses
// this list with util.RejectExplicitNulls to enforce the contract before
// the typed unmarshal collapses null into nil.
var JbacNonNullableUpdateFields = []string{
	"name", "pathPattern", "methods", "enforcement", "parentImpliesChildren",
}

// JbacRulesFilter holds query parameters for listing JBAC rules (tenant-scoped)
type JbacRulesFilter struct {
	Name        string `form:"name"`
	Enforcement string `form:"enforcement"`
	Limit       int    `form:"limit"  binding:"min=0,max=100"`
	Offset      int    `form:"offset" binding:"min=0,max=10000"`
}

// JbacRuleListResponse represents the response for a list of JBAC rules
type JbacRuleListResponse struct {
	Rules  []*JbacRule `json:"rules"`
	Limit  int         `json:"limit"`
	Offset int         `json:"offset"`
	Total  int         `json:"total"`
}

// JbacRuleResponse represents the response for a single JBAC rule
type JbacRuleResponse struct {
	Rule *JbacRule `json:"rule"`
}

// JbacRuleValidationResponse represents the response for a rule validation request
type JbacRuleValidationResponse struct {
	Valid  bool     `json:"valid"`
	Errors []string `json:"errors,omitempty"`
}

// BulkCreateJbacRulesRequest represents the request to create multiple JBAC rules
type BulkCreateJbacRulesRequest struct {
	Rules []CreateJbacRuleRequest `json:"rules"`
}

// BulkCreateJbacRulesResponse — see BulkCreateRbacRulesResponse for the
// atomic-by-design rationale. No Failed / Errors fields.
type BulkCreateJbacRulesResponse struct {
	Created int `json:"created"`
}
