package validator

import (
	"encoding/json"
	"regexp"

	"individual/internal/common"
	"individual/internal/models"
)

// ValidateConfig enforces the spec's input rules for POST /configs:
//   - mobileRegex: ≤512 chars and must compile as a regex when non-empty
//   - nameRegex:   ≤512 chars and must compile as a regex when non-empty
//   - uniquenessCriteria: JSON string array, ≤2 items, and every value must be
//     one of the recognised criteria [mobileNumber, name] — an unknown value is
//     rejected with a 400 rather than silently ignored.
func (v *individualValidator) ValidateConfig(cfg *models.Config) error {
	if cfg == nil {
		return nil
	}

	if err := maxLen("mobileRegex", cfg.MobileRegex, configRegexMaxLen); err != nil {
		return err
	}
	if cfg.MobileRegex != "" {
		if _, err := regexp.Compile(cfg.MobileRegex); err != nil {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "mobileRegex",
				"message": "mobileRegex is not a valid regular expression: " + err.Error(),
			})
		}
	}

	if err := maxLen("nameRegex", cfg.NameRegex, configRegexMaxLen); err != nil {
		return err
	}
	if cfg.NameRegex != "" {
		if _, err := regexp.Compile(cfg.NameRegex); err != nil {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "nameRegex",
				"message": "nameRegex is not a valid regular expression: " + err.Error(),
			})
		}
	}

	if len(cfg.UniquenessCriteria) > 0 {
		var raw []string
		if err := json.Unmarshal(cfg.UniquenessCriteria, &raw); err != nil {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "uniquenessCriteria",
				"message": "uniquenessCriteria must be a JSON array of strings",
			})
		}
		if len(raw) > maxUniquenessCriteria {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   "uniquenessCriteria",
				"message": "uniquenessCriteria must contain at most 2 entries",
			})
		}
		for _, field := range raw {
			if !supportedUniquenessCriteria[field] {
				return common.ErrValidation.WithParams(map[string]interface{}{
					"field":   "uniquenessCriteria",
					"message": "unsupported value \"" + field + "\"; supported values are [mobileNumber, name]",
				})
			}
		}
	}

	return nil
}

// supportedUniquenessCriteria is the set of recognised uniquenessCriteria values.
// Config validation rejects anything else (400); enforcement in
// applyUniquenessCriteria handles exactly these — keep the two in sync.
var supportedUniquenessCriteria = map[string]bool{"mobileNumber": true, "name": true}
