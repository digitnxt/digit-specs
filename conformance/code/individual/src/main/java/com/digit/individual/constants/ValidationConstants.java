package com.digit.individual.constants;

import java.util.Set;

/**
 * Validation limits — the single place these caps live for {@code RequestValidator}.
 * String length caps are in UTF-8 bytes and mirror the DB column widths they guard.
 * (Baseline regex patterns are compiled constants in {@code RequestValidator}.)
 */
public final class ValidationConstants {

    private ValidationConstants() {}

    /**
     * Recognised uniquenessCriteria values. Config validation rejects anything else (400);
     * enforcement in {@code RequestValidator.applyUniquenessCriteria} handles exactly these —
     * keep the two in sync.
     */
    public static final Set<String> SUPPORTED_UNIQUENESS_CRITERIA = Set.of("mobileNumber", "name");

    /**
     * Spec-defined enum value sets — the single place these live. Membership is checked via the
     * isValid* helpers in RequestValidator (and gender also in IndividualController).
     */
    public static final Set<String> VALID_GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    public static final Set<String> VALID_IDENTIFIER_TYPES = Set.of(
            "NATIONAL_ID", "AADHAAR", "PASSPORT", "VOTER_ID", "PAN", "DRIVING_LICENSE", "SYSTEM_GENERATED");
    public static final Set<String> VALID_ADDRESS_TYPES = Set.of("PERMANENT", "CORRESPONDENCE");

    /**
     * Sanity bound on age / date-of-birth: the maximum plausible human lifespan. This is a
     * living-persons registry, so a value implying an impossible age (or a garbage/typo date)
     * is rejected. Bounds the age ceiling and the dateOfBirth floor.
     */
    public static final int MAX_AGE_YEARS = 150;

    // Collection-size caps.
    public static final int MAX_ADDRESSES = 16;
    public static final int MAX_IDENTIFIERS = 16;
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_ADDITIONAL_ATTRIBUTES = 50;
    public static final int MAX_UNIQUENESS_CRITERIA = 2; // only mobileNumber and name are recognised criteria

    // Individual string length caps.
    public static final int EMAIL_MAX_LEN = 254;
    public static final int NAME_MAX_LEN = 128; // givenName, familyName, fatherName, husbandName
    public static final int OTHER_NAMES_MAX_LEN = 256;
    public static final int MOBILE_MAX_LEN = 20; // mobileNumber, altContactNumber
    public static final int LOCALE_MAX_LEN = 16;
    public static final int PHOTO_MAX_LEN = 512;
    public static final int USER_ID_MAX_LEN = 64;
    public static final int CONFIG_REGEX_MAX_LEN = 512; // mobileRegex, nameRegex
    public static final int ATTR_KEY_MAX_LEN = 128;
    public static final int ATTR_VALUE_MAX_LEN = 1024;

    // Address field caps.
    public static final int ADDR_DOORNO_MAX_LEN = 64;
    public static final int ADDR_BUILDING_MAX_LEN = 128;
    public static final int ADDR_STREET_MAX_LEN = 128;
    public static final int ADDR_LANDMARK_MAX_LEN = 128;
    public static final int ADDR_LINE_MAX_LEN = 256; // addressLine1, addressLine2
    public static final int ADDR_CITY_MAX_LEN = 128;
    public static final int ADDR_REGION_MAX_LEN = 128;
    public static final int ADDR_COUNTRY_MAX_LEN = 64;
    public static final int ADDR_PINCODE_MAX_LEN = 16;
    public static final int ADDR_BOUNDARY_MAX_LEN = 64;

    // Identifier field caps.
    public static final int IDENTIFIER_ID_MAX_LEN = 64;
    public static final int IDENTIFIER_DOCTYPE_MAX_LEN = 64;
    public static final int IDENTIFIER_FILESTORE_MAX_LEN = 64;

    // Document field caps.
    public static final int DOCUMENT_TYPE_MIN_LEN = 2;
    public static final int DOCUMENT_TYPE_MAX_LEN = 64;
    public static final int FILESTORE_MIN_LEN = 2;
    public static final int FILESTORE_MAX_LEN = 64;
    public static final int DOCUMENT_UID_MAX_LEN = 64;

    // Geo-coordinate bounds (WGS84 degrees).
    public static final double LATITUDE_MIN = -90;
    public static final double LATITUDE_MAX = 90;
    public static final double LONGITUDE_MIN = -180;
    public static final double LONGITUDE_MAX = 180;
}
