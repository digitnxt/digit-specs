package validator

import (
	"fmt"

	"individual/internal/common"
	"individual/internal/models"
)

// validateAddresses applies per-entry checks per the v3 spec.
//   - At least one of doorNo, street, landmark, city is required per entry.
//   - type: enum [PERMANENT, CORRESPONDENCE] when supplied.
//   - latitude:  ∈ [-90, 90]
//   - longitude: ∈ [-180, 180]
//   - String fields are capped per spec:
//       doorNo ≤64, buildingName ≤128, street ≤128, landmark ≤128,
//       addressLine1 ≤256, addressLine2 ≤256, city ≤128, region ≤128,
//       country ≤64, pincode ≤16, boundaryCode ≤64
func (v *individualValidator) validateAddresses(addresses []models.Address) error {
	for i, a := range addresses {
		prefix := fmt.Sprintf("address[%d]", i)

		if a.DoorNo == "" && a.Street == "" && a.Landmark == "" && a.City == "" {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix,
				"message": "address requires at least one of doorNo, street, landmark, or city",
			})
		}

		if a.Type != "" && !isValidAddressType(a.Type) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".type",
				"value":   a.Type,
				"message": "address.type must be PERMANENT or CORRESPONDENCE",
			})
		}

		if err := maxLen(prefix+".doorNo", a.DoorNo, addrDoorNoMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".buildingName", a.BuildingName, addrBuildingMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".street", a.Street, addrStreetMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".landmark", a.Landmark, addrLandmarkMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".addressLine1", a.AddressLine1, addrLineMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".addressLine2", a.AddressLine2, addrLineMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".city", a.City, addrCityMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".region", a.Region, addrRegionMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".country", a.Country, addrCountryMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".pincode", a.Pincode, addrPincodeMaxLen); err != nil {
			return err
		}
		if err := maxLen(prefix+".boundaryCode", a.BoundaryCode, addrBoundaryMaxLen); err != nil {
			return err
		}

		if a.Latitude != nil && (*a.Latitude < latitudeMin || *a.Latitude > latitudeMax) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".latitude",
				"value":   *a.Latitude,
				"message": "latitude must be between -90 and 90",
			})
		}
		if a.Longitude != nil && (*a.Longitude < longitudeMin || *a.Longitude > longitudeMax) {
			return common.ErrValidation.WithParams(map[string]interface{}{
				"field":   prefix + ".longitude",
				"value":   *a.Longitude,
				"message": "longitude must be between -180 and 180",
			})
		}
	}
	return nil
}

// isValidAddressType returns true for the spec-defined Address.type enum (see validAddressTypes).
func isValidAddressType(t string) bool {
	return validAddressTypes[t]
}
