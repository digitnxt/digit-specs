package validator

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"accesscontrol/internal/constants"
	"accesscontrol/internal/model"
)

var (
	// Allowed HTTP methods. Wildcard '*' is intentionally excluded: the Kong
	// authorization plugin keys its cache by the literal request method, so a
	// rule with method '*' would never match any request and would be dead data.
	validHTTPMethods    = map[string]bool{"GET": true, "POST": true, "PUT": true, "DELETE": true, "PATCH": true}
	allowedMethodsLabel = "GET, POST, PUT, DELETE, PATCH"
	staticSegmentRegex  = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	validParamRegex     = regexp.MustCompile(`^\{param:(UUID|ALNUM)\}$`)
	// Role names: alphanumeric, underscore, hyphen only. No spaces or special characters
	validRoleNameRegex = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	// UUID validation: standard UUID format
	validUUIDRegex = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

// ValidateRbacRequest validates a RBAC rule for both create and update.
func ValidateRbacRequest(rule interface{}) (bool, []string) {
	var errors []string

	switch r := rule.(type) {
	case *model.CreateRbacRuleRequest:
		errors = append(errors, validateHTTPMethod(r.HTTPMethod)...)
		errors = append(errors, validateRoleNames(r.RoleNames)...)
		errors = append(errors, validatePath(r.Path)...)
		errors = append(errors, validateEffect(r.Effect)...)
		// Priority is optional on Create — the handler fills nil with
		// DefaultPriority before this runs, so under normal flow it's never
		// nil here. The guard is defensive against future callers that
		// bypass the defaults helper.
		if r.Priority != nil {
			errors = append(errors, validatePriority(*r.Priority)...)
		}
		errors = append(errors, validateDescription(r.Description)...)
		errors = append(errors, validateConstraints(r.Constraints)...)

	case *model.UpdateRbacRuleRequest:
		if r.HTTPMethod != nil {
			errors = append(errors, validateHTTPMethod(*r.HTTPMethod)...)
		}
		if r.RoleNames != nil {
			errors = append(errors, validateRoleNames(*r.RoleNames)...)
		}
		if r.Path != nil {
			errors = append(errors, validatePath(*r.Path)...)
		}
		if r.Effect != nil {
			errors = append(errors, validateEffect(*r.Effect)...)
		}
		if r.Priority != nil {
			errors = append(errors, validatePriority(*r.Priority)...)
		}
		// Nullable fields: validate the value only when explicitly present and
		// not null. A null sent here means "clear", which needs no validation.
		if r.Description.Set && !r.Description.Null {
			errors = append(errors, validateDescription(r.Description.Value)...)
		}
		if r.Constraints.Set && !r.Constraints.Null {
			errors = append(errors, validateConstraints(r.Constraints.Value)...)
		}
	default:
		errors = append(errors, "Invalid rule type for RBAC validation")
	}

	return len(errors) == 0, errors
}

func validateHTTPMethod(method string) []string {
	if method == "" {
		return []string{"httpMethod is required"}
	}
	if _, ok := validHTTPMethods[method]; !ok {
		return []string{fmt.Sprintf("Invalid httpMethod %q. Allowed values: %s", method, allowedMethodsLabel)}
	}
	return nil
}

func validateRoleNames(roleNames []string) []string {
	var errors []string
	if len(roleNames) == 0 {
		errors = append(errors, "roleNames must be a non-empty array")
		return errors
	}
	if len(roleNames) > constants.MaxRoleNamesPerRule {
		errors = append(errors, fmt.Sprintf("roleNames must contain at most %d entries (got %d)", constants.MaxRoleNamesPerRule, len(roleNames)))
	}
	for _, roleName := range roleNames {
		if roleName == "" {
			errors = append(errors, "roleNames cannot contain empty strings")
			continue
		}
		if roleName == "*" {
			errors = append(errors, "roleNames cannot contain wildcard '*'")
			continue
		}
		if len(roleName) > constants.MaxRoleNameLength {
			errors = append(errors, fmt.Sprintf("Invalid role name %q: must be at most %d characters", roleName, constants.MaxRoleNameLength))
			continue
		}
		// Validate role name format: only alphanumeric, underscore, hyphen
		if !validRoleNameRegex.MatchString(roleName) {
			errors = append(errors, fmt.Sprintf("Invalid role name: %s. Role names must contain only alphanumeric characters, underscores, and hyphens (no spaces or special characters)", roleName))
		}
		// Check for leading/trailing spaces
		if roleName != strings.TrimSpace(roleName) {
			errors = append(errors, fmt.Sprintf("Invalid role name: %s. Role names cannot have leading or trailing spaces", roleName))
		}
	}
	return errors
}

func validatePath(path string) []string {
	var errors []string

	if path == "" {
		return []string{"path is required"}
	}

	// 1. Length cap (before any other checks to avoid expensive ops on huge input)
	if len(path) > constants.MaxPathLength {
		errors = append(errors, fmt.Sprintf("path must be at most %d characters (got %d)", constants.MaxPathLength, len(path)))
		return errors
	}

	// 2. Must start with /
	if !strings.HasPrefix(path, "/") {
		errors = append(errors, "Path must start with '/'")
	}

	// 3. Must NOT contain ? or #
	if strings.Contains(path, "?") {
		errors = append(errors, "Path must NOT contain '?' (query parameters not allowed)")
	}
	if strings.Contains(path, "#") {
		errors = append(errors, "Path must NOT contain '#' (fragments not allowed)")
	}

	// 4. Must NOT contain //
	if strings.Contains(path, "//") {
		errors = append(errors, "Path must NOT contain '//' (double slashes not allowed)")
	}

	// 5. Split into segments and validate each
	segments := strings.Split(path, "/")
	nonEmptySegments := 0
	for i, segment := range segments {
		// Skip empty segments (first one from leading /)
		if segment == "" {
			continue
		}
		nonEmptySegments++

		// Check if it's the last segment
		isLastSegment := i == len(segments)-1

		// Validate segment type
		if segment == "*" {
			// Wildcard only allowed as last segment
			if !isLastSegment {
				errors = append(errors, "Wildcard '*' is only allowed as the last segment")
			}
		} else if strings.HasPrefix(segment, "{") && strings.HasSuffix(segment, "}") {
			// Parameter segment
			if !validParamRegex.MatchString(segment) {
				errors = append(errors, fmt.Sprintf("Invalid parameter format: %s. Must be {param:UUID} or {param:ALNUM}", segment))
			}
		} else {
			// Static segment
			if !staticSegmentRegex.MatchString(segment) {
				errors = append(errors, fmt.Sprintf("Invalid static segment: %s. Must contain only [A-Za-z0-9_-]", segment))
			}
		}
	}

	// 6. Must contain at least one non-empty segment (reject bare "/")
	if nonEmptySegments == 0 {
		errors = append(errors, "path must contain at least one segment after '/'")
	}

	// 7. Cap path depth
	if nonEmptySegments > constants.MaxPathSegments {
		errors = append(errors, fmt.Sprintf("path must contain at most %d segments (got %d)", constants.MaxPathSegments, nonEmptySegments))
	}

	return errors
}

func validateEffect(effect string) []string {
	if effect == "" {
		return []string{"effect is required"}
	}
	if effect != "ALLOW" && effect != "DENY" {
		return []string{fmt.Sprintf("Invalid effect %q. Allowed values: ALLOW, DENY", effect)}
	}
	return nil
}

func validatePriority(priority int) []string {
	if priority < 0 {
		return []string{fmt.Sprintf("Priority must be a non-negative integer (got %d). Priority cannot be negative", priority)}
	}
	if priority > constants.MaxPriority {
		return []string{fmt.Sprintf("Priority must be at most %d (got %d)", constants.MaxPriority, priority)}
	}
	return nil
}

func validateDescription(description string) []string {
	if len(description) > constants.MaxDescriptionLength {
		return []string{fmt.Sprintf("description must be at most %d characters (got %d)", constants.MaxDescriptionLength, len(description))}
	}
	return nil
}

func validateConstraints(constraints json.RawMessage) []string {
	return validateJSONField("constraints", constraints)
}

// validateJSONField checks size + parseability of any JSONB column payload.
// Used for both RBAC.constraints and JBAC.extractJurisdiction.
func validateJSONField(fieldName string, raw json.RawMessage) []string {
	if len(raw) == 0 {
		return nil
	}
	if len(raw) > constants.MaxJSONFieldBytes {
		return []string{fmt.Sprintf("%s must be at most %d bytes (got %d)", fieldName, constants.MaxJSONFieldBytes, len(raw))}
	}
	if !json.Valid(raw) {
		return []string{fmt.Sprintf("%s must be a valid JSON value", fieldName)}
	}
	if bytes.TrimSpace(raw)[0] != '{' {
		return []string{fmt.Sprintf("%s must be a JSON object", fieldName)}
	}
	return nil
}

func ValidateRuleID(id string) []string {
	var errors []string
	if id == "" {
		errors = append(errors, "Rule ID cannot be empty")
		return errors
	}
	if !validUUIDRegex.MatchString(strings.ToLower(id)) {
		errors = append(errors, fmt.Sprintf("Invalid rule ID format: %s. Rule ID must be a valid UUID (e.g., 550e8400-e29b-41d4-a716-446655440000)", id))
	}
	return errors
}
