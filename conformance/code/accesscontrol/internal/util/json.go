// Package util contains small, dependency-light helpers reused across the
// service. Keep functions here generic — anything domain-specific (RBAC/JBAC
// shapes, defaults, validation) belongs alongside the model or in the
// validator package, not here.
package util

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// RejectExplicitNulls inspects a PATCH JSON body and returns a validation
// message for every top-level field name in `nonNullable` that was sent as
// JSON `null`.
//
// This closes the gap that Go's standard json unmarshal leaves open:
// pointer-typed fields cannot distinguish "absent" from "null" — both decode
// to nil. By peeking at the raw map before the typed unmarshal we can reject
// `null` on required fields without changing the typed model.
//
// Returns nil if the body is not a JSON object (the typed unmarshal will
// surface the parse error separately).
func RejectExplicitNulls(body []byte, nonNullable []string) []string {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(body, &raw); err != nil {
		return nil
	}

	var errs []string
	for _, field := range nonNullable {
		v, present := raw[field]
		if !present {
			continue
		}
		if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
			errs = append(errs, fmt.Sprintf("%s cannot be null; omit the field or send a valid value", field))
		}
	}
	return errs
}
