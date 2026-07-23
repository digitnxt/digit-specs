package com.digit.accesscontrol.constants;

/**
 * Validation limits and server-side defaults. Mirrors Go internal/constants/constants.go exactly.
 */
public final class Constants {
    private Constants() {}

    // Permission effects
    public static final String ALLOW_PERMISSION = "ALLOW";
    public static final String DENY_PERMISSION = "DENY";

    // String lengths
    public static final int MAX_ROLE_NAME_LENGTH = 64;   // matches billing "longer code" convention
    public static final int MAX_PATH_LENGTH = 256;       // matches billing "name" convention
    public static final int MAX_DESCRIPTION_LENGTH = 256; // matches billing "name" convention
    public static final int MAX_TENANT_ID_LENGTH = 64;

    // Array / structural caps
    public static final int MAX_ROLE_NAMES_PER_RULE = 32;
    public static final int MAX_PATH_SEGMENTS = 20;
    public static final int MAX_BULK_RULES_PER_REQUEST = 500;

    // JSON payload caps — applies to RBAC.constraints and JBAC.extractJurisdiction
    public static final int MAX_JSON_FIELD_BYTES = 4096; // 4 KB cap on any JSONB column

    // Numeric caps (mirror billing-schema.yaml's int32 ceiling)
    public static final int MAX_PRIORITY = 2147483647;

    // Server-side defaults applied to optional fields on Create.
    public static final int DEFAULT_PRIORITY = MAX_PRIORITY;
    public static final boolean DEFAULT_ENABLED = true;
}
