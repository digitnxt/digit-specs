package com.digit.account.constants;

/**
 * Validation limits. Mirrors Go internal/constants/constants.go verbatim.
 */
public final class Constants {
    private Constants() {}

    // Identifiers, names, contact strings
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_CODE_LENGTH = 128;
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 256;
    public static final int MAX_DESCRIPTION_LENGTH = 256;

    // Listing / search query parameter caps
    public static final int MAX_QUERY_FILTER_LENGTH = 256;
    public static final int MAX_PAGE_SIZE = 100;

    // Contact / address fields (v3 spec)
    public static final int MAX_PHONE_LENGTH = 20;
    public static final int MIN_ADDRESS_LENGTH = 5;
    public static final int MAX_ADDRESS_LENGTH = 512;
    public static final int MIN_CITY_LENGTH = 1;
    public static final int MAX_CITY_LENGTH = 128;
    public static final int MIN_STATE_LENGTH = 1;
    public static final int MAX_STATE_LENGTH = 128;
    public static final int MIN_COUNTRY_LENGTH = 2;
    public static final int MAX_COUNTRY_LENGTH = 128;
    public static final int MIN_PINCODE_LENGTH = 5;
    public static final int MAX_PINCODE_LENGTH = 10;

    // additionalAttributes structural caps
    public static final int MAX_ADDITIONAL_ATTRIBUTES_COUNT = 50;
    public static final int MAX_ADDITIONAL_ATTRIBUTES_KEY_LENGTH = 128;
    public static final int MAX_ADDITIONAL_ATTRIBUTES_VALUE_LENGTH = 1024;

    // Signup / OTP
    public static final int MAX_REQUEST_ID_LENGTH = 36;
    public static final int MIN_REFERENCE_ID_LENGTH = 10;
    public static final int MAX_REFERENCE_ID_LENGTH = 2048;
    public static final int MIN_OTP_LENGTH = 4;
    public static final int MAX_OTP_LENGTH = 8;
    public static final String OTP_PURPOSE_REGISTRATION = "registration";

    // TenantConfig (v3 key/value store)
    public static final int MIN_CONFIG_KEY_LENGTH = 1;
    public static final int MAX_CONFIG_KEY_LENGTH = 256;
    public static final int MIN_CONFIG_VALUE_LENGTH = 1;
    public static final int MAX_CONFIG_VALUE_LENGTH = 2048;
    public static final int MIN_CONFIG_DESCRIPTION_LENGTH = 1;
    public static final int MAX_CONFIG_DESCRIPTION_LENGTH = 512;

    // JSON payload caps
    public static final int MAX_JSON_FIELD_BYTES = 4096;
}
