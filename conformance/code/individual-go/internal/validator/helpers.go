package validator

import (
	"fmt"
	"regexp"

	"individual/internal/common"
)

// maxLen returns a validation error when value exceeds the spec-defined cap.
// Empty values pass through — required-ness is checked separately so optional
// fields stay optional.
func maxLen(field, value string, max int) error {
	if len(value) > max {
		return common.ErrValidation.WithContext(map[string]interface{}{
			"field":   field,
			"message": fmt.Sprintf("%s must not exceed %d characters", field, max),
		})
	}
	return nil
}

// checkPattern validates value against the tenant-configured regex when one is
// present (and compilable); otherwise it falls back to the platform baseline.
// An empty value passes — required-ness is enforced separately, and length /
// structural caps are applied by the caller and always hold regardless of which
// pattern is used. This is where "tenant config overrides the baseline" is
// implemented, per-field: a configured tenant pattern replaces the baseline for
// that field rather than stacking on top of it.
func checkPattern(field, value, tenantRegex string, baseline *regexp.Regexp, baselineMsg string) error {
	if value == "" {
		return nil
	}
	if tenantRegex != "" {
		if re, err := regexp.Compile(tenantRegex); err == nil {
			if !re.MatchString(value) {
				return common.ErrValidation.WithContext(map[string]interface{}{
					"field":   field,
					"value":   value,
					"message": field + " does not match the configured pattern for this tenant",
				})
			}
			return nil
		}
		// Tenant regex failed to compile (should be caught at config write time);
		// fall back to the platform baseline rather than silently accepting.
	}
	if !baseline.MatchString(value) {
		return common.ErrValidation.WithContext(map[string]interface{}{
			"field":   field,
			"value":   value,
			"message": baselineMsg,
		})
	}
	return nil
}

// isValidGender returns true for the spec-defined gender enum values (see validGenders).
func isValidGender(gender string) bool {
	return validGenders[gender]
}
