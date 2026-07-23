package model

import (
	"database/sql/driver"
	"encoding/json"

	"accesscontrol/internal/constants"

	"github.com/lib/pq"
)

type StringArray []string

func (a *StringArray) Scan(value interface{}) error {
	return pq.Array((*[]string)(a)).Scan(value)
}

func (a StringArray) Value() (driver.Value, error) {
	return pq.Array([]string(a)).Value()
}

// Rule represents an RBAC rule
type Rule struct {
	ID           string          `json:"id" gorm:"type:uuid;primary_key"`
	TenantID     string          `json:"tenantId" gorm:"column:tenant_id"`
	RoleNames    StringArray     `json:"roleNames" gorm:"column:role_names;type:text[]"`
	HTTPMethod   string          `json:"httpMethod" gorm:"column:http_method"`
	Path         string          `json:"path" gorm:"column:path"`
	Effect       string          `json:"effect" gorm:"column:effect"`
	Priority     int             `json:"priority" gorm:"column:priority"`
	Enabled      bool            `json:"enabled" gorm:"column:enabled"`
	Constraints  json.RawMessage `json:"constraints,omitempty" gorm:"type:jsonb"`
	Description  string          `json:"description,omitempty" gorm:"column:description"`
	RequestID    string          `json:"requestId,omitempty"   gorm:"column:requestid"`
	AuditDetails AuditDetail     `json:"auditDetails" gorm:"embedded"`
}

func (Rule) TableName() string {
	return "access_rbac_rules_v3"
}

// CreateRbacRuleRequest represents the request to create a new RBAC rule.
//
// Priority and Enabled are pointers so we can tell "client omitted the field"
// (nil) from "client sent zero/false" (non-nil with the zero value).
// ApplyDefaults fills nil values with the server-side defaults from
// constants; callers must invoke it before validation/persistence.
type CreateRbacRuleRequest struct {
	RoleNames   []string        `json:"roleNames"`
	HTTPMethod  string          `json:"httpMethod"`
	Path        string          `json:"path"`
	Effect      string          `json:"effect"`
	Priority    *int            `json:"priority,omitempty"`
	Enabled     *bool           `json:"enabled,omitempty"`
	Constraints json.RawMessage `json:"constraints,omitempty"`
	Description string          `json:"description,omitempty"`
}

// ApplyDefaults populates optional fields that the caller may legitimately
// omit. Defaults are sourced from the constants package so OpenAPI and code
// share a single value.
func (r *CreateRbacRuleRequest) ApplyDefaults() {
	if r.Priority == nil {
		p := constants.DefaultPriority
		r.Priority = &p
	}
	if r.Enabled == nil {
		e := constants.DefaultEnabled
		r.Enabled = &e
	}
}

// RbacNonNullableUpdateFields lists the top-level fields on
// UpdateRbacRuleRequest that must not accept JSON `null`. The handler uses
// this list with util.RejectExplicitNulls to enforce the contract before
// the typed unmarshal collapses null into nil.
var RbacNonNullableUpdateFields = []string{
	"roleNames", "httpMethod", "path", "effect", "priority", "enabled",
}

// UpdateRbacRuleRequest represents a PATCH-style partial update of an RBAC rule.
//
// Required fields use plain pointers: absent = don't touch, present = update.
// JSON `null` on any of these is rejected by the handler (see rejectExplicitNulls).
//
// Optional clearable fields (constraints, description) use Nullable[T] to
// distinguish "absent" from "explicit null = clear column".
type UpdateRbacRuleRequest struct {
	RoleNames   *StringArray              `json:"roleNames,omitempty"`
	HTTPMethod  *string                   `json:"httpMethod,omitempty"`
	Path        *string                   `json:"path,omitempty"`
	Effect      *string                   `json:"effect,omitempty"`
	Priority    *int                      `json:"priority,omitempty"`
	Enabled     *bool                     `json:"enabled,omitempty"`
	Constraints Nullable[json.RawMessage] `json:"constraints,omitempty"`
	Description Nullable[string]          `json:"description,omitempty"`
}

// RbacRulesFilter holds query parameters for listing RBAC rules (tenant-scoped)
type RbacRulesFilter struct {
	RoleName   string `form:"roleName"`
	HTTPMethod string `form:"httpMethod"`
	Effect     string `form:"effect"`
	Enabled    *bool  `form:"enabled"`
	Limit      int    `form:"limit"  binding:"min=0,max=100"`
	Offset     int    `form:"offset" binding:"min=0,max=10000"`
}

// AllRulesFilter holds query parameters for internal list endpoints (all tenants)
type AllRulesFilter struct {
	Limit  int `form:"limit"  binding:"min=0,max=1000"`
	Offset int `form:"offset" binding:"min=0,max=100000"`
}

// RbacRuleListResponse represents the response for a list of rules
type RbacRuleListResponse struct {
	Rules  []*Rule `json:"rules"`
	Limit  int     `json:"limit"`
	Offset int     `json:"offset"`
	Total  int     `json:"total"`
}

// RbacRuleResponse represents the response for a single rule
type RbacRuleResponse struct {
	Rule *Rule `json:"rule"`
}

// RuleValidationResponse represents the response for a rule validation request
type RuleValidationResponse struct {
	Valid  bool     `json:"valid"`
	Errors []string `json:"errors,omitempty"`
}

// BulkCreateRbacRulesRequest represents the request to create multiple RBAC rules
type BulkCreateRbacRulesRequest struct {
	Rules []CreateRbacRuleRequest `json:"rules"`
}

// BulkCreateRbacRulesResponse is returned by the bulk-create endpoint.
// The operation is atomic — either all rules are created (201 with the
// count) or none are (4xx/5xx with an Error array). There is no partial-
// success path, so this struct intentionally has no Failed / Errors fields.
type BulkCreateRbacRulesResponse struct {
	Created int `json:"created"`
}
