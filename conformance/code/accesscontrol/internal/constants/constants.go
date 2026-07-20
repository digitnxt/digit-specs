package constants

const (
	// Permission effects
	AllowPermission = "ALLOW"
	DenyPermission  = "DENY"
)

// Validation limits. These values are the single source of truth for input
// caps across the validator, OpenAPI schema, and any client SDKs. Lengths
// follow the conventions used in billing-schema.yaml so the platform stays
// consistent (short codes: 32, longer codes: 64, names/descriptions: 128/256).
const (
	// String lengths
	MaxRoleNameLength    = 64  // matches billing "longer code" convention
	MaxPathLength        = 256 // matches billing "name" convention
	MaxDescriptionLength = 256 // matches billing "name" convention
	MaxTenantIDLength    = 64

	// Array / structural caps
	MaxRoleNamesPerRule    = 32  // sane cap on roles per rule
	MaxPathSegments        = 20  // sane cap on path depth
	MaxBulkRulesPerRequest = 500 // covers tenant init (~230 today) with headroom

	// JSON payload caps — applies to RBAC.constraints and JBAC.extractJurisdiction
	MaxJSONFieldBytes = 4096 // 4 KB cap on any JSONB column

	// Numeric caps (mirror billing-schema.yaml's int32 ceiling)
	MaxPriority = 2147483647
)

// Server-side defaults applied to optional fields on Create. These are the
// single source of truth — keep `default:` in schema.yaml in sync.
const (
	// DefaultPriority sends newly-created rules to the bottom of evaluation
	// order. Kong evaluates lower priority first; rules without an explicit
	// priority shouldn't accidentally outrank existing ones.
	DefaultPriority = MaxPriority

	// DefaultEnabled — newly-created rules are active by default.
	DefaultEnabled = true
)
