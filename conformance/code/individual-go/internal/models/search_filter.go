package models

// IndividualSearchFilter is the wire-layer shape for GET /individuals query
// parameters. Validation is declared via the `binding` tags and executed by
// Gin's ShouldBindQuery in the handler — no separate validation pass needed.
//
// Field rules per v3 spec:
//   - id, individualId: repeatable, OR-ed semantics
//   - givenName: partial case-insensitive match (1–128 chars)
//   - mobileNumber: exact match, ≤20 chars
//   - gender: enum MALE | FEMALE | OTHER (case-sensitive)
//   - dateOfBirth: ISO YYYY-MM-DD
//   - page: ≥1 (default applied in handler)
//   - size: 1–100 (default applied in handler)
type IndividualSearchFilter struct {
	ID             []string `form:"id"             binding:"omitempty,dive,uuid"`
	IndividualID   []string `form:"individualId"   binding:"omitempty,dive,max=64"`
	GivenName      string   `form:"givenName"      binding:"omitempty,min=1,max=128"`
	MobileNumber   string   `form:"mobileNumber"   binding:"omitempty,max=20"`
	Gender         string   `form:"gender"         binding:"omitempty,oneof=MALE FEMALE OTHER"`
	DateOfBirth    string   `form:"dateOfBirth"    binding:"omitempty,datetime=2006-01-02"`
	IncludeDeleted bool  `form:"includeDeleted"`
	// *int so we can distinguish "absent" (nil → handler applies default)
	// from "explicit 0" (pointer-to-0 → validator rejects via min=1).
	// See bug.md #14.
	Page *int `form:"page"           binding:"omitempty,min=1"`
	Size *int `form:"size"           binding:"omitempty,min=1,max=100"`
}

// ToSearchCriteria converts the wire filter into the internal SearchCriteria
// used by the service/repo layers. Returns nil when no filter was supplied.
func (f *IndividualSearchFilter) ToSearchCriteria() *SearchCriteria {
	c := &SearchCriteria{
		GivenName:   f.GivenName,
		Gender:      f.Gender,
		DateOfBirth: f.DateOfBirth,
	}
	if len(f.ID) > 0 {
		c.ID = f.ID
	}
	if len(f.IndividualID) > 0 {
		c.IndividualID = f.IndividualID
	}
	if f.MobileNumber != "" {
		c.MobileNumber = []string{f.MobileNumber}
	}
	return c
}

// IndividualExistsFilter is the wire-layer shape for GET /individuals/exists.
// Existence check is scoped to a single individual — id and individualId are
// single-valued (the array form valid on search is rejected here by the
// `string` field type plus Gin's first-wins binding).
//
// Page/size are accepted but ignored per spec.
type IndividualExistsFilter struct {
	ID             string `form:"id"             binding:"omitempty,uuid"`
	IndividualID   string `form:"individualId"   binding:"omitempty,max=64"`
	GivenName      string `form:"givenName"      binding:"omitempty,min=1,max=128"`
	MobileNumber   string `form:"mobileNumber"   binding:"omitempty,max=20"`
	Gender         string `form:"gender"         binding:"omitempty,oneof=MALE FEMALE OTHER"`
	DateOfBirth    string `form:"dateOfBirth"    binding:"omitempty,datetime=2006-01-02"`
	IncludeDeleted bool `form:"includeDeleted"`
	// Same nil-vs-zero distinction as IndividualSearchFilter — see bug.md #14.
	// Page/size are accepted but ignored on this endpoint, but we still
	// reject explicit 0 for consistency.
	Page *int `form:"page"           binding:"omitempty,min=1"`
	Size *int `form:"size"           binding:"omitempty,min=1,max=100"`
}

// HasFilter reports whether at least one searchable field was supplied.
// includeDeleted, page, and size do NOT count — per spec the exists endpoint
// requires a real filter to scope the check.
func (f *IndividualExistsFilter) HasFilter() bool {
	return f.ID != "" ||
		f.IndividualID != "" ||
		f.GivenName != "" ||
		f.MobileNumber != "" ||
		f.Gender != "" ||
		f.DateOfBirth != ""
}

// ToSearchCriteria converts the wire filter into the internal SearchCriteria.
func (f *IndividualExistsFilter) ToSearchCriteria() *SearchCriteria {
	c := &SearchCriteria{
		GivenName:   f.GivenName,
		Gender:      f.Gender,
		DateOfBirth: f.DateOfBirth,
	}
	if f.ID != "" {
		c.ID = []string{f.ID}
	}
	if f.IndividualID != "" {
		c.IndividualID = []string{f.IndividualID}
	}
	if f.MobileNumber != "" {
		c.MobileNumber = []string{f.MobileNumber}
	}
	return c
}
