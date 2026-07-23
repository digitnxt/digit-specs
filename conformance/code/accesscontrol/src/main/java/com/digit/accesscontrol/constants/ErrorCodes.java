package com.digit.accesscontrol.constants;

/** Error codes. Mirrors the literal codes used across the Go handlers/routes/model. */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String MISSING_TENANT_ID = "AccessControl.MissingTenantId";
    public static final String MISSING_USER_ID = "AccessControl.MissingUserId";
    public static final String INVALID_REQUEST = "AccessControl.InvalidRequest";
    public static final String VALIDATION_FAILED = "AccessControl.ValidationFailed";
    public static final String NOT_FOUND = "AccessControl.NotFound";
    public static final String INTERNAL_ERROR = "AccessControl.InternalError";
}
