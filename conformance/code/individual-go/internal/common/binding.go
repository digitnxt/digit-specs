package common

import (
	"errors"
	"fmt"

	"individual/internal/models"

	"github.com/go-playground/validator/v10"
)

// BindingErrors converts a Gin/validator binding error into the spec-shaped
// []models.Error returned as the 400 response body. For validator-level
// failures it emits one element per offending field; for non-validator
// errors (malformed input, type mismatches) it returns a single generic
// INVALID_REQUEST entry.
func BindingErrors(err error) []models.Error {
	var ve validator.ValidationErrors
	if errors.As(err, &ve) {
		out := make([]models.Error, 0, len(ve))
		for _, fe := range ve {
			out = append(out, models.Error{
				Code:    ErrorValidation,
				Message: fmt.Sprintf("field '%s' failed '%s' validation", fe.Field(), fe.Tag()),
				Params: map[string]interface{}{
					"field": fe.Field(),
					"tag":   fe.Tag(),
				},
			})
		}
		return out
	}
	return []models.Error{{
		Code:        "INVALID_REQUEST",
		Message:     "Invalid request",
		Description: err.Error(),
	}}
}
