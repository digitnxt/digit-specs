package validator

import "regexp"

// Baseline validation patterns and limits — the single place these live for the validator.
//
// Patterns use RE2 (regexp), a linear-time, no-backtracking engine, so a tenant-configured
// regex that overrides a baseline can never cause catastrophic backtracking (ReDoS). Keep all
// regex handling on this engine for that guarantee to hold.
var (
	emailRegex             = regexp.MustCompile(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`)
	alphaOnly              = regexp.MustCompile(`^[a-zA-Z\s]+$`)
	additionalAttrKeyRegex = regexp.MustCompile(`^[a-zA-Z0-9_.\-]+$`)
	mobileBaseline         = regexp.MustCompile(`^[0-9]{6,15}$`)
)

// Spec-defined enum value sets — the single place these live for the validator.
// Membership is checked via the isValid* helpers (helpers.go, identifier.go, address.go).
var (
	validGenders         = map[string]bool{"MALE": true, "FEMALE": true, "OTHER": true}
	validIdentifierTypes = map[string]bool{
		"NATIONAL_ID": true, "AADHAAR": true, "PASSPORT": true, "VOTER_ID": true,
		"PAN": true, "DRIVING_LICENSE": true, "SYSTEM_GENERATED": true,
	}
	validAddressTypes = map[string]bool{"PERMANENT": true, "CORRESPONDENCE": true}
)

const (
	// maxAgeYears is a sanity bound on age / date-of-birth: the maximum plausible human
	// lifespan. This is a living-persons registry, so a value implying an impossible age
	// (or a garbage/typo date) is rejected. Bounds the age ceiling and the dateOfBirth floor.
	maxAgeYears = 150

	// Collection-size caps.
	maxAddresses            = 16
	maxIdentifiers          = 16
	maxDocuments            = 20
	maxAdditionalAttributes = 50
	maxUniquenessCriteria   = 2 // only mobileNumber and name are recognised criteria

	// Individual string length caps (UTF-8 bytes) — mirror the DB column widths they guard.
	emailMaxLen       = 254
	nameMaxLen        = 128 // givenName, familyName, fatherName, husbandName
	otherNamesMaxLen  = 256
	mobileMaxLen      = 20 // mobileNumber, altContactNumber
	localeMaxLen      = 16
	photoMaxLen       = 512
	userIDMaxLen      = 64
	configRegexMaxLen = 512 // mobileRegex, nameRegex
	attrKeyMaxLen     = 128
	attrValueMaxLen   = 1024

	// Address field caps.
	addrDoorNoMaxLen   = 64
	addrBuildingMaxLen = 128
	addrStreetMaxLen   = 128
	addrLandmarkMaxLen = 128
	addrLineMaxLen     = 256 // addressLine1, addressLine2
	addrCityMaxLen     = 128
	addrRegionMaxLen   = 128
	addrCountryMaxLen  = 64
	addrPincodeMaxLen  = 16
	addrBoundaryMaxLen = 64

	// Identifier field caps.
	identifierIDMaxLen        = 64
	identifierDocTypeMaxLen   = 64
	identifierFileStoreMaxLen = 64

	// Document field caps.
	documentTypeMinLen = 2
	documentTypeMaxLen = 64
	fileStoreMinLen    = 2
	fileStoreMaxLen    = 64
	documentUIDMaxLen  = 64
)

// Geo-coordinate bounds (WGS84 degrees).
const (
	latitudeMin  = -90.0
	latitudeMax  = 90.0
	longitudeMin = -180.0
	longitudeMax = 180.0
)