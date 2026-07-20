package model

import "encoding/json"

// Nullable wraps an optional PATCH field so we can distinguish all three
// caller intents that JSON otherwise conflates:
//
//   - Field absent from the request body   → Set=false   (don't touch the column)
//   - Field present as JSON null           → Set=true,  Null=true   (clear the column)
//   - Field present with a value           → Set=true,  Null=false, Value=<v>
//
// Standard Go pointer fields cannot express the second state — both "absent"
// and "null" decode to nil. Nullable[T] uses a custom UnmarshalJSON to record
// presence at decode time, before any null collapsing happens.
type Nullable[T any] struct {
	Set   bool
	Null  bool
	Value T
}

// UnmarshalJSON is invoked only when the field is present in the JSON body.
// Therefore Set is always true here. We then check whether the literal `null`
// was sent and either record that or decode the inner value.
func (n *Nullable[T]) UnmarshalJSON(data []byte) error {
	n.Set = true
	if string(data) == "null" {
		n.Null = true
		return nil
	}
	return json.Unmarshal(data, &n.Value)
}
