package models

// IndividualSearchRequest represents search request
type IndividualSearchRequest struct {
	Individual     *SearchCriteria `json:"individual,omitempty"`
	Page           int             `json:"page,omitempty"`
	Size           int             `json:"size,omitempty"`
	IncludeDeleted bool            `json:"includeDeleted,omitempty"`
}

// SearchCriteria represents search parameters
type SearchCriteria struct {
	ID             []string          `json:"id,omitempty"`
	IndividualID   []string          `json:"individualId,omitempty"`
	IndividualName string            `json:"individualName,omitempty"`
	GivenName      string            `json:"givenName,omitempty"`
	MobileNumber   []string          `json:"mobileNumber,omitempty"`
	Gender         string            `json:"gender,omitempty"`
	DateOfBirth    string            `json:"dateOfBirth,omitempty"`
	Identifier     *IdentifierSearch `json:"identifier,omitempty"`
	BoundaryCode   string            `json:"boundaryCode,omitempty"`
	WardCode       string            `json:"wardCode,omitempty"`
	Latitude       *float64          `json:"latitude,omitempty"`
	Longitude      *float64          `json:"longitude,omitempty"`
	SearchRadius   *float64          `json:"searchRadius,omitempty"`
	SocialCategory string            `json:"socialCategory,omitempty"`
	CreatedFrom    *int64            `json:"createdFrom,omitempty"`
	CreatedTo      *int64            `json:"createdTo,omitempty"`
	RoleCodes      []string          `json:"roleCodes,omitempty"`
	Username       []string          `json:"username,omitempty"`
	UserID         []string          `json:"userId,omitempty"`
	UserUUID       []string          `json:"userUuid,omitempty"`
}

// IdentifierSearch represents identifier search criteria
type IdentifierSearch struct {
	IdentifierType string `json:"identifierType,omitempty"`
	IdentifierID   string `json:"identifierId,omitempty"`
}

// IndividualSearchResponse represents search response. Individuals are
// wire-layer DTOs.
type IndividualSearchResponse struct {
	TotalCount  int64           `json:"totalCount"`
	Page        int             `json:"page"`
	Size        int             `json:"size"`
	HasMore     bool            `json:"hasMore"`
	Individuals []IndividualDTO `json:"individuals"`
}

// ExistsResponse is the response payload for GET /individuals/exists.
type ExistsResponse struct {
	Exists bool `json:"exists"`
}

// DeleteResponse is the response payload for DELETE /individuals/{id}.
type DeleteResponse struct {
	Deleted bool `json:"deleted"`
}

// Error is a single error entry; responses return a []Error array. Shape matches
// the platform common Error (code/message/description/params) — params is an
// optional string array (placeholders), left unset by this service; specific
// detail is carried in message.
type Error struct {
	Code        string   `json:"code"`
	Message     string   `json:"message"`
	Description string   `json:"description,omitempty"`
	Params      []string `json:"params,omitempty"`
}

// RequestContext holds data extracted from headers
type RequestContext struct {
	TenantID  string
	UserID    string
	RequestID string
}
