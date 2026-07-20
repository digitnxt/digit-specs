package models

// AddressDTO is the wire shape per v3 spec.
// Notably absent vs. the entity: tenantId, clientReferenceId, formatted,
// wardCode (the latter three were dropped entirely in wave C).
type AddressDTO struct {
	ID               string   `json:"id"`
	Type             string   `json:"type,omitempty"`
	DoorNo           string   `json:"doorNo,omitempty"`
	BuildingName     string   `json:"buildingName,omitempty"`
	Street           string   `json:"street,omitempty"`
	Landmark         string   `json:"landmark,omitempty"`
	AddressLine1     string   `json:"addressLine1,omitempty"`
	AddressLine2     string   `json:"addressLine2,omitempty"`
	City             string   `json:"city,omitempty"`
	Region           string   `json:"region,omitempty"`
	Country          string   `json:"country,omitempty"`
	Pincode          string   `json:"pincode,omitempty"`
	BoundaryCode     string   `json:"boundaryCode,omitempty"`
	Latitude         *float64 `json:"latitude,omitempty"`
	Longitude        *float64 `json:"longitude,omitempty"`
	LocationAccuracy *float64 `json:"locationAccuracy,omitempty"`

	RequestID   string       `json:"requestId,omitempty"`
	AuditDetail *AuditDetail `json:"auditDetail,omitempty"`
}

func AddressFromEntity(e *Address) *AddressDTO {
	if e == nil {
		return nil
	}
	return &AddressDTO{
		ID:               e.ID,
		Type:             e.Type,
		DoorNo:           e.DoorNo,
		BuildingName:     e.BuildingName,
		Street:           e.Street,
		Landmark:         e.Landmark,
		AddressLine1:     e.AddressLine1,
		AddressLine2:     e.AddressLine2,
		City:             e.City,
		Region:           e.Region,
		Country:          e.Country,
		Pincode:          e.Pincode,
		BoundaryCode:     e.BoundaryCode,
		Latitude:         e.Latitude,
		Longitude:        e.Longitude,
		LocationAccuracy: e.LocationAccuracy,
		RequestID:        e.RequestID,
		AuditDetail:      newAuditDetail(e.CreatedBy, e.ModifiedBy, e.CreatedTime, e.ModifiedTime),
	}
}

func AddressToEntity(d *AddressDTO) *Address {
	if d == nil {
		return nil
	}
	return &Address{
		ID:               d.ID,
		Type:             d.Type,
		DoorNo:           d.DoorNo,
		BuildingName:     d.BuildingName,
		Street:           d.Street,
		Landmark:         d.Landmark,
		AddressLine1:     d.AddressLine1,
		AddressLine2:     d.AddressLine2,
		City:             d.City,
		Region:           d.Region,
		Country:          d.Country,
		Pincode:          d.Pincode,
		BoundaryCode:     d.BoundaryCode,
		Latitude:         d.Latitude,
		Longitude:        d.Longitude,
		LocationAccuracy: d.LocationAccuracy,
		// TenantID is set by the enrichment service from the parent Individual.
		// Audit fields are set by enrichment.
	}
}
