package models

// IndividualDTO is the wire-layer shape per the v3 spec.
// Notes:
//   - tenantId is sourced from the X-Tenant-ID header, not the body — absent here.
//   - hashedMobileNumber is internal — absent here.
//   - rowVersion is exposed as "version" per spec.
//   - active is exposed as "isActive" per spec.
//   - additionalDetails (DB column) is exposed as "additionalAttributes" per spec.
//   - auditDetail is readOnly (set by mapper on response; ignored on request).
type IndividualDTO struct {
	ID                   string     `json:"id"`
	IndividualID         string     `json:"individualId,omitempty"`
	GivenName            string     `json:"givenName,omitempty"`
	FamilyName           string     `json:"familyName,omitempty"`
	OtherNames           string     `json:"otherNames,omitempty"`
	DateOfBirth          *Date      `json:"dateOfBirth,omitempty"`
	Gender               string     `json:"gender,omitempty"`
	Age                  *int       `json:"age,omitempty"`
	MobileNumber         string     `json:"mobileNumber,omitempty"`
	MobileNumberVerified bool       `json:"mobileNumberVerified"`
	AltContactNumber     string     `json:"altContactNumber,omitempty"`
	Email                string     `json:"email,omitempty"`
	EmailVerified        bool       `json:"emailVerified"`
	Locale               string     `json:"locale,omitempty"`
	IsActive             bool       `json:"isActive"`
	FatherName           string     `json:"fatherName,omitempty"`
	HusbandName          string     `json:"husbandName,omitempty"`
	Photo                string     `json:"photo,omitempty"`
	UserID               string     `json:"userId,omitempty"`
	AdditionalAttributes JSONB      `json:"additionalAttributes,omitempty"`
	Version              int        `json:"version,omitempty"`
	RequestID            string     `json:"requestId,omitempty"`

	AuditDetail *AuditDetail `json:"auditDetail,omitempty"`

	Addresses   []AddressDTO    `json:"address,omitempty"`
	Identifiers []IdentifierDTO `json:"identifiers,omitempty"`
	Documents   []DocumentDTO   `json:"documents,omitempty"`
}

// IndividualFromEntity converts a DB entity into a wire DTO.
// Flat audit columns are assembled into the nested AuditDetail.
func IndividualFromEntity(e *Individual) *IndividualDTO {
	if e == nil {
		return nil
	}
	d := &IndividualDTO{
		ID:                   e.ID,
		IndividualID:         e.IndividualID,
		GivenName:            e.GivenName,
		FamilyName:           e.FamilyName,
		OtherNames:           e.OtherNames,
		DateOfBirth:          DateFromTimePtr(e.DateOfBirth),
		Gender:               e.Gender,
		Age:                  e.Age,
		MobileNumber:         e.MobileNumber,
		MobileNumberVerified: e.MobileNumberVerified,
		AltContactNumber:     e.AltContactNumber,
		Email:                e.Email,
		EmailVerified:        e.EmailVerified,
		Locale:               e.Locale,
		IsActive:             e.Active,
		FatherName:           e.FatherName,
		HusbandName:          e.HusbandName,
		Photo:                e.Photo,
		UserID:               e.UserID,
		AdditionalAttributes: e.AdditionalDetails,
		Version:              e.RowVersion,
		RequestID:            e.RequestID,
		AuditDetail:          newAuditDetail(e.CreatedBy, e.ModifiedBy, e.CreatedTime, e.ModifiedTime),
	}
	for i := range e.Addresses {
		if dto := AddressFromEntity(&e.Addresses[i]); dto != nil {
			d.Addresses = append(d.Addresses, *dto)
		}
	}
	for i := range e.Identifiers {
		if dto := IdentifierFromEntity(&e.Identifiers[i]); dto != nil {
			d.Identifiers = append(d.Identifiers, *dto)
		}
	}
	for i := range e.Documents {
		if dto := DocumentFromEntity(&e.Documents[i]); dto != nil {
			d.Documents = append(d.Documents, *dto)
		}
	}
	return d
}

// IndividualsFromEntities converts a slice of entities to a slice of DTOs.
func IndividualsFromEntities(es []Individual) []IndividualDTO {
	out := make([]IndividualDTO, 0, len(es))
	for i := range es {
		if dto := IndividualFromEntity(&es[i]); dto != nil {
			out = append(out, *dto)
		}
	}
	return out
}

// IndividualToEntity converts a wire DTO into a DB entity.
// Server-managed fields (TenantID from header, audit fields from enrichment,
// HashedMobileNumber from encryption) are NOT populated here.
func IndividualToEntity(d *IndividualDTO) *Individual {
	if d == nil {
		return nil
	}
	e := &Individual{
		ID:                   d.ID,
		IndividualID:         d.IndividualID,
		GivenName:            d.GivenName,
		FamilyName:           d.FamilyName,
		OtherNames:           d.OtherNames,
		DateOfBirth:          d.DateOfBirth.ToTimePtr(),
		Gender:               d.Gender,
		Age:                  d.Age,
		MobileNumber:         d.MobileNumber,
		MobileNumberVerified: d.MobileNumberVerified,
		AltContactNumber:     d.AltContactNumber,
		Email:                d.Email,
		EmailVerified:        d.EmailVerified,
		Locale:               d.Locale,
		Active:               d.IsActive,
		FatherName:           d.FatherName,
		HusbandName:          d.HusbandName,
		Photo:                d.Photo,
		UserID:               d.UserID,
		AdditionalDetails:    d.AdditionalAttributes,
		RowVersion:           d.Version,
		RequestID:            d.RequestID,
	}
	for i := range d.Addresses {
		if ent := AddressToEntity(&d.Addresses[i]); ent != nil {
			e.Addresses = append(e.Addresses, *ent)
		}
	}
	for i := range d.Identifiers {
		if ent := IdentifierToEntity(&d.Identifiers[i]); ent != nil {
			e.Identifiers = append(e.Identifiers, *ent)
		}
	}
	for i := range d.Documents {
		if ent := DocumentToEntity(&d.Documents[i]); ent != nil {
			e.Documents = append(e.Documents, *ent)
		}
	}
	return e
}
