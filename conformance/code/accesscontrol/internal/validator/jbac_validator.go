package validator

import (
	"fmt"
	"strings"

	"accesscontrol/internal/constants"
	"accesscontrol/internal/model"
)

var (
	validEnforcementTypes  = map[string]bool{"REQUIRED": true, "OPTIONAL": true, "DISABLED": true}
	allowedEnforcementList = "REQUIRED, OPTIONAL, DISABLED"
)

// ValidateJbacRequest validates a JBAC rule for both create and update.
func ValidateJbacRequest(rule interface{}) (bool, []string) {
	var errors []string

	switch r := rule.(type) {
	case *model.CreateJbacRuleRequest:
		errors = append(errors, validateJbacName(r.Name)...)
		errors = append(errors, validateJbacMethods(r.Methods)...)
		errors = append(errors, validatePath(r.PathPattern)...)
		errors = append(errors, validateEnforcement(r.Enforcement)...)
		errors = append(errors, validateDescription(r.Description)...)
		errors = append(errors, validateJSONField("extractJurisdiction", r.ExtractJurisdiction)...)

	case *model.UpdateJbacRuleRequest:
		if r.Name != nil {
			errors = append(errors, validateJbacName(*r.Name)...)
		}
		if r.Methods != nil {
			errors = append(errors, validateJbacMethods(*r.Methods)...)
		}
		if r.PathPattern != nil {
			errors = append(errors, validatePath(*r.PathPattern)...)
		}
		if r.Enforcement != nil {
			errors = append(errors, validateEnforcement(*r.Enforcement)...)
		}
		// Nullable fields: validate the value only when explicitly present and
		// not null. A null sent here means "clear", which needs no validation.
		if r.Description.Set && !r.Description.Null {
			errors = append(errors, validateDescription(r.Description.Value)...)
		}
		if r.ExtractJurisdiction.Set && !r.ExtractJurisdiction.Null {
			errors = append(errors, validateJSONField("extractJurisdiction", r.ExtractJurisdiction.Value)...)
		}
	default:
		errors = append(errors, "Invalid rule type for JBAC validation")
	}

	return len(errors) == 0, errors
}

func validateJbacName(name string) []string {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return []string{"name is required"}
	}
	if len(name) > constants.MaxRoleNameLength {
		return []string{fmt.Sprintf("name must be at most %d characters (got %d)", constants.MaxRoleNameLength, len(name))}
	}
	return nil
}

func validateJbacMethods(methods []string) []string {
	var errors []string
	if len(methods) == 0 {
		return []string{"methods must be a non-empty array"}
	}
	if len(methods) > constants.MaxRoleNamesPerRule {
		errors = append(errors, fmt.Sprintf("methods must contain at most %d entries (got %d)", constants.MaxRoleNamesPerRule, len(methods)))
	}
	for _, m := range methods {
		errors = append(errors, validateHTTPMethod(m)...)
	}
	return errors
}

func validateEnforcement(enforcement string) []string {
	if enforcement == "" {
		return []string{"enforcement is required"}
	}
	if _, ok := validEnforcementTypes[enforcement]; !ok {
		return []string{fmt.Sprintf("Invalid enforcement %q. Allowed values: %s", enforcement, allowedEnforcementList)}
	}
	return nil
}
