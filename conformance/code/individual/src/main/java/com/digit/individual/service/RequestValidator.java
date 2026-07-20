package com.digit.individual.service;

import com.digit.individual.constants.ErrorCodes;
import static com.digit.individual.constants.ValidationConstants.*;
import com.digit.individual.model.Address;
import com.digit.individual.model.Config;
import com.digit.individual.model.Document;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import com.digit.individual.repository.ConfigRepository;
import com.digit.individual.repository.IndividualRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * Request validation: required-field, format, and business-rule checks, nested entity validation,
 * plus tenant-config-driven regex overrides and uniqueness enforcement.
 */
@Component
public class RequestValidator {

    private static final Pattern EMAIL = Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern ALPHA_ONLY = Pattern.compile("^[a-zA-Z\\s]+$");
    private static final Pattern ADDITIONAL_ATTR_KEY = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");
    private static final Pattern MOBILE_BASELINE = Pattern.compile("^[0-9]{6,15}$");

    private final IndividualRepository repo;
    private final ConfigRepository cfgRepo;
    private final JsonMapper mapper;
    // Pepper for the mobile blind-index lookup; must match the secret EncryptionService uses so a
    // validation-time hash lookup finds rows written at encrypt time. Empty allowed only Vault-off.
    private final byte[] hmacSecret;

    public RequestValidator(IndividualRepository repo, ConfigRepository cfgRepo, JsonMapper mapper,
                            com.digit.individual.config.IndividualProperties props) {
        this.repo = repo;
        this.cfgRepo = cfgRepo;
        this.mapper = mapper;
        this.hmacSecret = props.getHmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- entry points

    public void validateCreate(Individual ind) {
        validateRequiredFields(ind);
        Config cfg = tenantConfig(ind.getTenantId());
        validateFormats(ind, cfg);
        validateBusinessRules(ind, cfg, true);
    }

    public void validateUpdate(Individual ind) {
        if (ind.getId() == null || ind.getId().isEmpty()) {
            throw validation("id is required for update");
        }
        // version is required on update: individual is an optimistic-concurrency API, so every PUT
        // must carry the version it is based on (valid versions are >= 1).
        if (ind.getRowVersion() <= 0) {
            throw validation("version is required for update");
        }
        validateRequiredFields(ind);

        Individual existing = repo.findById(ind.getId(), ind.getTenantId());
        if (existing == null) {
            throw new CustomException(ErrorCodes.NON_EXISTENT_ENTITY, "Individual not found", HttpStatus.NOT_FOUND);
        }
        // Optimistic-concurrency fast-fail: version is required (checked above) and must match the
        // current row.
        if (existing.getRowVersion() != ind.getRowVersion()) {
            throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "Row version mismatch", HttpStatus.CONFLICT);
        }
        Config cfg = tenantConfig(ind.getTenantId());
        validateFormats(ind, cfg);
        validateBusinessRules(ind, cfg, false);
    }

    public void validateDelete(Individual ind) {
        if (ind.getId() == null || ind.getId().isEmpty()) {
            throw validation("id is required for delete");
        }
        Individual existing = repo.findById(ind.getId(), ind.getTenantId());
        if (existing == null || !existing.isActive()) {
            throw new CustomException(ErrorCodes.NON_EXISTENT_ENTITY, "Individual not found", HttpStatus.NOT_FOUND);
        }
    }

    public void validateConfig(Config cfg) {
        if (cfg == null) {
            return;
        }
        maxLen("mobileRegex", cfg.getMobileRegex(), CONFIG_REGEX_MAX_LEN);
        if (cfg.getMobileRegex() != null && !cfg.getMobileRegex().isEmpty()) {
            try {
                Pattern.compile(cfg.getMobileRegex());
            } catch (PatternSyntaxException e) {
                throw field("mobileRegex", "mobileRegex is not a valid regular expression: " + e.getMessage());
            }
        }
        maxLen("nameRegex", cfg.getNameRegex(), CONFIG_REGEX_MAX_LEN);
        if (cfg.getNameRegex() != null && !cfg.getNameRegex().isEmpty()) {
            try {
                Pattern.compile(cfg.getNameRegex());
            } catch (PatternSyntaxException e) {
                throw field("nameRegex", "nameRegex is not a valid regular expression: " + e.getMessage());
            }
        }
        if (cfg.getUniquenessCriteria() != null && !cfg.getUniquenessCriteria().isEmpty()) {
            List<?> raw;
            try {
                raw = mapper.readValue(cfg.getUniquenessCriteria(), List.class);
            } catch (Exception e) {
                throw field("uniquenessCriteria", "uniquenessCriteria must be a JSON array of strings");
            }
            if (raw.size() > MAX_UNIQUENESS_CRITERIA) {
                throw field("uniquenessCriteria", "uniquenessCriteria must contain at most 2 entries");
            }
            for (Object o : raw) {
                if (!(o instanceof String s) || !SUPPORTED_UNIQUENESS_CRITERIA.contains(s)) {
                    throw field("uniquenessCriteria",
                            "unsupported value '" + o + "'; supported values are [mobileNumber, name]");
                }
            }
        }
    }

    // ----------------------------------------------------------- field rules

    private void validateRequiredFields(Individual ind) {
        if (isEmpty(ind.getGivenName())) {
            throw field("givenName", "givenName is required");
        }
        if (isEmpty(ind.getTenantId())) {
            throw field("tenantId", "tenantId is required (from X-Tenant-ID header)");
        }
        if (ind.getGender() == null || ind.getGender().trim().isEmpty()) {
            throw field("gender", "gender is required");
        }
    }

    private void validateFormats(Individual ind, Config cfg) {
        // Per-field pattern source: a configured tenant regex overrides the baseline.
        String mobileRegex = cfg != null ? cfg.getMobileRegex() : null;
        String nameRegex = cfg != null ? cfg.getNameRegex() : null;

        if (!isEmpty(ind.getEmail())) {
            maxLen("email", ind.getEmail(), EMAIL_MAX_LEN);
            if (!EMAIL.matcher(ind.getEmail()).matches()) {
                throw fieldValue("email", ind.getEmail(), "email must be a valid email address, e.g. name@example.com");
            }
        }
        if (!isEmpty(ind.getGender()) && !isValidGender(ind.getGender())) {
            throw fieldValue("gender", ind.getGender(), "gender must be MALE, FEMALE, or OTHER");
        }
        if (!isEmpty(ind.getGivenName())) {
            maxLen("givenName", ind.getGivenName(), NAME_MAX_LEN);
            checkPattern("givenName", ind.getGivenName(), nameRegex, ALPHA_ONLY,
                    "givenName must contain only alphabets and spaces");
        }
        if (!isEmpty(ind.getFamilyName())) {
            maxLen("familyName", ind.getFamilyName(), NAME_MAX_LEN);
            checkPattern("familyName", ind.getFamilyName(), nameRegex, ALPHA_ONLY,
                    "familyName must contain only alphabets and spaces");
        }
        maxLen("otherNames", ind.getOtherNames(), OTHER_NAMES_MAX_LEN);
        maxLen("mobileNumber", ind.getMobileNumber(), MOBILE_MAX_LEN);
        checkPattern("mobileNumber", ind.getMobileNumber(), mobileRegex, MOBILE_BASELINE,
                "mobileNumber must be 6-15 digits");
        maxLen("altContactNumber", ind.getAltContactNumber(), MOBILE_MAX_LEN);
        maxLen("locale", ind.getLocale(), LOCALE_MAX_LEN);
        maxLen("fatherName", ind.getFatherName(), NAME_MAX_LEN);
        maxLen("husbandName", ind.getHusbandName(), NAME_MAX_LEN);
        maxLen("photo", ind.getPhoto(), PHOTO_MAX_LEN);
        maxLen("userId", ind.getUserId(), USER_ID_MAX_LEN);

        if (ind.getAge() != null && (ind.getAge() < 0 || ind.getAge() > MAX_AGE_YEARS)) {
            throw fieldValue("age", ind.getAge(), "age must be between 0 and 150");
        }

        // dateOfBirth is date-granular (yyyy-MM-dd): strictly-future dates are rejected, today is
        // allowed, and dates more than 150 years in the past are rejected. A birth date has no
        // meaningful time component, so date granularity is intentional.
        if (ind.getDateOfBirth() != null) {
            LocalDate dob = ind.getDateOfBirth();
            LocalDate today = LocalDate.now();
            if (dob.isAfter(today)) {
                throw fieldValue("dateOfBirth", dob.toString(), "dateOfBirth must not be in the future");
            }
            if (dob.isBefore(today.minusYears(MAX_AGE_YEARS))) {
                throw fieldValue("dateOfBirth", dob.toString(),
                        "dateOfBirth must not be more than 150 years in the past");
            }
        }

        validateAdditionalAttributes(ind.getAdditionalDetails());
    }

    private void validateAdditionalAttributes(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return;
        }
        if (attrs.size() > MAX_ADDITIONAL_ATTRIBUTES) {
            throw field("additionalAttributes", "additionalAttributes must contain at most 50 entries");
        }
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            String key = e.getKey();
            if (byteLen(key) > ATTR_KEY_MAX_LEN) {
                throw field("additionalAttributes." + key,
                        "additionalAttributes key must not exceed 128 characters");
            }
            if (!ADDITIONAL_ATTR_KEY.matcher(key).matches()) {
                throw field("additionalAttributes." + key,
                        "additionalAttributes keys must match ^[a-zA-Z0-9_.-]+$");
            }
            if (!(e.getValue() instanceof String s)) {
                throw field("additionalAttributes." + key,
                        "additionalAttributes values must be strings");
            } else if (byteLen(s) > ATTR_VALUE_MAX_LEN) {
                throw field("additionalAttributes." + key,
                        "additionalAttributes value must not exceed 1024 characters");
            }
        }
    }

    // ----------------------------------------------------------- business rules

    private void validateBusinessRules(Individual ind, Config cfg, boolean isCreate) {
        if (isEmpty(ind.getMobileNumber()) && isEmpty(ind.getEmail())) {
            throw field("mobileNumber/email", "at least one of mobileNumber or email is required");
        }
        if (ind.getAddresses().size() > MAX_ADDRESSES) {
            throw field("address", "address must contain at most 16 entries");
        }
        if (ind.getIdentifiers().size() > MAX_IDENTIFIERS) {
            throw field("identifiers", "identifiers must contain at most 16 entries");
        }

        applyUniquenessCriteria(ind, cfg, isCreate);

        if (!ind.getIdentifiers().isEmpty()) {
            validateIdentifiers(ind.getIdentifiers());
        }
        if (!ind.getAddresses().isEmpty()) {
            validateAddresses(ind.getAddresses());
        }
        if (!ind.getDocuments().isEmpty()) {
            validateDocuments(ind.getDocuments());
        }
    }

    /** Loads the per-tenant validation config, or null when none is set (or the repo is unavailable). */
    private Config tenantConfig(String tenantId) {
        if (cfgRepo == null || isEmpty(tenantId)) {
            return null;
        }
        try {
            return cfgRepo.getByTenant(tenantId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns an existing individual sharing this mobile number in the tenant, or null. Validation
     * runs before the mobile is encrypted, so hashedMobileNumber is usually empty here — we compute
     * the hash on the fly and try the hash lookup first (covers encrypted-at-rest tenants), then fall
     * back to the plaintext lookup for tenants that store plaintext.
     */
    private Individual mobileDuplicate(Individual ind) {
        if (isEmpty(ind.getMobileNumber())) {
            return null;
        }
        String hash = HashUtil.hashMobileNumber(hmacSecret, ind.getMobileNumber());
        if (!hash.isEmpty()) {
            Individual existing = repo.findByMobileHash(hash, ind.getTenantId());
            if (existing != null) {
                return existing;
            }
        }
        return repo.findByMobilePlain(ind.getMobileNumber(), ind.getTenantId());
    }

    /**
     * Enforces natural-key uniqueness ONLY for the fields a tenant opts into via config
     * uniquenessCriteria. With no config, no natural-key uniqueness is enforced — id / individualId
     * remain unique via their own keys.
     */
    private void applyUniquenessCriteria(Individual ind, Config cfg, boolean isCreate) {
        if (cfg == null) {
            return;
        }
        List<String> criteria = parseCriteria(cfg.getUniquenessCriteria());
        for (String f : criteria) {
            switch (f.toLowerCase()) {
                case "mobilenumber" -> {
                    Individual existing = mobileDuplicate(ind);
                    if (existing != null && (isCreate || !existing.getId().equals(ind.getId()))) {
                        throw new CustomException(ErrorCodes.UNIQUE_ENTITY, "mobileNumber already exists for this tenant", HttpStatus.CONFLICT);
                    }
                }
                case "name" -> {
                    if (isEmpty(ind.getGivenName()) && isEmpty(ind.getFamilyName())) {
                        continue;
                    }
                    Individual existing = repo.findByName(ind.getGivenName(), ind.getFamilyName(), ind.getTenantId());
                    if (existing != null && (isCreate || !existing.getId().equals(ind.getId()))) {
                        throw new CustomException(ErrorCodes.UNIQUE_ENTITY, "name already exists for this tenant", HttpStatus.CONFLICT);
                    }
                }
                default -> { /* ignore unknown criteria */ }
            }
        }
    }

    private List<String> parseCriteria(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            List<?> raw = mapper.readValue(json, List.class);
            List<String> out = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ----------------------------------------------------------- nested entities

    private void validateAddresses(List<Address> addresses) {
        for (int i = 0; i < addresses.size(); i++) {
            Address a = addresses.get(i);
            String prefix = "address[" + i + "]";
            if (isEmpty(a.getDoorNo()) && isEmpty(a.getStreet()) && isEmpty(a.getLandmark()) && isEmpty(a.getCity())) {
                throw field(prefix, "address requires at least one of doorNo, street, landmark, or city");
            }
            if (!isEmpty(a.getType()) && !isValidAddressType(a.getType())) {
                throw fieldValue(prefix + ".type", a.getType(), "address.type must be PERMANENT or CORRESPONDENCE");
            }
            maxLen(prefix + ".doorNo", a.getDoorNo(), ADDR_DOORNO_MAX_LEN);
            maxLen(prefix + ".buildingName", a.getBuildingName(), ADDR_BUILDING_MAX_LEN);
            maxLen(prefix + ".street", a.getStreet(), ADDR_STREET_MAX_LEN);
            maxLen(prefix + ".landmark", a.getLandmark(), ADDR_LANDMARK_MAX_LEN);
            maxLen(prefix + ".addressLine1", a.getAddressLine1(), ADDR_LINE_MAX_LEN);
            maxLen(prefix + ".addressLine2", a.getAddressLine2(), ADDR_LINE_MAX_LEN);
            maxLen(prefix + ".city", a.getCity(), ADDR_CITY_MAX_LEN);
            maxLen(prefix + ".region", a.getRegion(), ADDR_REGION_MAX_LEN);
            maxLen(prefix + ".country", a.getCountry(), ADDR_COUNTRY_MAX_LEN);
            maxLen(prefix + ".pincode", a.getPincode(), ADDR_PINCODE_MAX_LEN);
            maxLen(prefix + ".boundaryCode", a.getBoundaryCode(), ADDR_BOUNDARY_MAX_LEN);
            if (a.getLatitude() != null && (a.getLatitude() < LATITUDE_MIN || a.getLatitude() > LATITUDE_MAX)) {
                throw fieldValue(prefix + ".latitude", a.getLatitude(), "latitude must be between -90 and 90");
            }
            if (a.getLongitude() != null && (a.getLongitude() < LONGITUDE_MIN || a.getLongitude() > LONGITUDE_MAX)) {
                throw fieldValue(prefix + ".longitude", a.getLongitude(), "longitude must be between -180 and 180");
            }
        }
    }

    private void validateIdentifiers(List<Identifier> identifiers) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < identifiers.size(); i++) {
            Identifier id = identifiers.get(i);
            String prefix = "identifiers[" + i + "]";
            if (isEmpty(id.getIdentifierType())) {
                throw field(prefix + ".identifierType", "identifierType is required");
            }
            if (!isValidIdentifierType(id.getIdentifierType())) {
                throw fieldValue(prefix + ".identifierType", id.getIdentifierType(),
                        "identifierType must be one of NATIONAL_ID, AADHAAR, PASSPORT, VOTER_ID, PAN, DRIVING_LICENSE, SYSTEM_GENERATED");
            }
            if (seen.contains(id.getIdentifierType())) {
                throw field("identifiers", "duplicate identifierType: " + id.getIdentifierType());
            }
            seen.add(id.getIdentifierType());
            if (isEmpty(id.getIdentifierId())) {
                throw field(prefix + ".identifierId", "identifierId is required");
            }
            maxLen(prefix + ".identifierId", id.getIdentifierId(), IDENTIFIER_ID_MAX_LEN);
            maxLen(prefix + ".documentType", id.getDocumentType(), IDENTIFIER_DOCTYPE_MAX_LEN);
            maxLen(prefix + ".fileStoreId", id.getFileStoreId(), IDENTIFIER_FILESTORE_MAX_LEN);
        }
    }

    private void validateDocuments(List<Document> documents) {
        if (documents.size() > MAX_DOCUMENTS) {
            throw field("documents", "documents must contain at most 20 entries");
        }
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            String prefix = "documents[" + i + "]";
            if (isEmpty(d.getDocumentType())) {
                throw field(prefix + ".documentType", "documentType is required");
            }
            if (byteLen(d.getDocumentType()) < DOCUMENT_TYPE_MIN_LEN || byteLen(d.getDocumentType()) > DOCUMENT_TYPE_MAX_LEN) {
                throw field(prefix + ".documentType", "documentType must be 2-64 characters");
            }
            if (isEmpty(d.getFileStoreId())) {
                throw field(prefix + ".fileStoreId", "fileStoreId is required");
            }
            if (byteLen(d.getFileStoreId()) < FILESTORE_MIN_LEN || byteLen(d.getFileStoreId()) > FILESTORE_MAX_LEN) {
                throw field(prefix + ".fileStoreId", "fileStoreId must be 2-64 characters");
            }
            maxLen(prefix + ".documentUid", d.getDocumentUid(), DOCUMENT_UID_MAX_LEN);
        }
    }

    // ----------------------------------------------------------- helpers

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static void maxLen(String fieldName, String value, int max) {
        if (value != null && byteLen(value) > max) {
            throw field(fieldName, fieldName + " must not exceed " + max + " characters");
        }
    }

    /**
     * Validates value against the tenant-configured regex when one is present (and compilable);
     * otherwise the platform baseline. An empty value passes — required-ness is enforced separately,
     * and length/structural caps are applied by the caller and always hold regardless of the pattern
     * source. This is where a configured tenant pattern REPLACES the baseline for that field,
     * per-field, rather than stacking on top of it.
     */
    private static void checkPattern(String fieldName, String value, String tenantRegex,
                                     Pattern baseline, String baselineMsg) {
        if (isEmpty(value)) {
            return;
        }
        Pattern re = baseline;
        if (!isEmpty(tenantRegex)) {
            try {
                re = Pattern.compile(tenantRegex);
            } catch (PatternSyntaxException e) {
                re = baseline; // invalid tenant regex (should be caught at config write time) -> baseline
            }
        }
        if (!re.matcher(value).find()) {
            String msg = (re == baseline)
                    ? baselineMsg
                    : fieldName + " does not match the configured pattern for this tenant";
            throw fieldValue(fieldName, value, msg);
        }
    }

    /**
     * UTF-8 byte length. Length caps are defined in bytes, so a multi-byte value
     * (CJK / emoji / accented) is measured in bytes here, not UTF-16 code units.
     */
    private static int byteLen(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean isValidGender(String g) {
        return g != null && VALID_GENDERS.contains(g);
    }

    private static boolean isValidAddressType(String t) {
        return t != null && VALID_ADDRESS_TYPES.contains(t);
    }

    private static boolean isValidIdentifierType(String t) {
        return t != null && VALID_IDENTIFIER_TYPES.contains(t);
    }

    /** Generic single-message validation error (VALIDATION_ERROR code, HTTP 400 via tracer). */
    private static CustomException validation(String message) {
        return new CustomException(ErrorCodes.VALIDATION_ERROR, message);
    }

    /** Field validation error. Code is VALIDATION_ERROR; the message already names the field. */
    private static CustomException field(String name, String message) {
        return new CustomException(ErrorCodes.VALIDATION_ERROR, message);
    }

    /** Like {@link #field}; the offending value is expected to be folded into {@code message} by the caller. */
    private static CustomException fieldValue(String name, Object value, String message) {
        return new CustomException(ErrorCodes.VALIDATION_ERROR, message);
    }
}
