package models

import (
	"strings"
	"time"

	"github.com/araddon/dateparse"
)

// Date is a wire-friendly wrapper around time.Time. Parsing is delegated to
// araddon/dateparse, which accepts the natural "YYYY-MM-DD" form (preferred
// for birth-date inputs) as well as RFC3339 and other common layouts.
// Marshals as "YYYY-MM-DD" because dates carry no time-of-day. See bug.md #3a.
type Date struct {
	time.Time
}

const dateLayout = "2006-01-02"

func (d *Date) UnmarshalJSON(data []byte) error {
	s := strings.Trim(string(data), `"`)
	if s == "" || s == "null" {
		return nil
	}
	t, err := dateparse.ParseAny(s)
	if err != nil {
		return err
	}
	d.Time = t
	return nil
}

func (d Date) MarshalJSON() ([]byte, error) {
	if d.IsZero() {
		return []byte("null"), nil
	}
	return []byte(`"` + d.Format(dateLayout) + `"`), nil
}

// ToTimePtr returns a *time.Time view of d (nil if zero). Used at the
// DTO→entity boundary where downstream code already expects *time.Time.
func (d *Date) ToTimePtr() *time.Time {
	if d == nil || d.IsZero() {
		return nil
	}
	t := d.Time
	return &t
}

// DateFromTimePtr lifts a *time.Time into *Date for the entity→DTO boundary.
func DateFromTimePtr(t *time.Time) *Date {
	if t == nil || t.IsZero() {
		return nil
	}
	return &Date{Time: *t}
}
