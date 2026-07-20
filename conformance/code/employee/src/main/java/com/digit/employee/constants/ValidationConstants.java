package com.digit.employee.constants;

/**
 * Validation limits and pagination bounds — the single place these caps live. String length caps
 * mirror the employee_v3 column widths they guard (see the widen-columns migration); the batch and
 * pagination bounds mirror Go's binding tags / OpenAPI. Keep in sync with the Go service.
 */
public final class ValidationConstants {
    private ValidationConstants() {}

    /** Max employees creatable in one POST (Go maxCreateBatch / OpenAPI maxItems). */
    public static final int MAX_CREATE_BATCH = 100;

    // Employee field length caps (VARCHAR widths).
    public static final int EMPLOYEE_TYPE_MAX_LEN = 128;
    public static final int DEPARTMENT_MAX_LEN = 128;
    public static final int DESIGNATION_MAX_LEN = 128;
    public static final int CODE_MAX_LEN = 64;
    public static final int USER_ID_MAX_LEN = 64;
    public static final int INDIVIDUAL_ID_MAX_LEN = 64;
    public static final int STATUS_MAX_LEN = 64;

    /** Earliest plausible appointment year — dates before this are rejected (Go minDateOfAppointment). */
    public static final int MIN_APPOINTMENT_YEAR = 1900;

    // Search pagination bounds (Go binding: limit min=1 max=100, offset min=0).
    public static final int DEFAULT_LIMIT = 10;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 100;
    public static final int MIN_OFFSET = 0;

    /** Keycloak role-member lookup page size (Go keycloakRoleMemberPageSize). */
    public static final int KEYCLOAK_ROLE_MEMBER_PAGE_SIZE = 100;
}