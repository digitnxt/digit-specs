package com.digit.account.validator;

import com.digit.account.constants.Constants;
import com.digit.account.model.TenantCreateRequest;
import com.digit.account.model.TenantEntity;
import com.digit.account.model.TenantUpdateRequest;
import com.digit.account.model.Mappers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Tenant validation. Mirrors Go internal/validator/tenant_validator.go (byte-length semantics). */
public final class TenantValidator {
    private TenantValidator() {}

    private static final Pattern EMAIL_REGEX =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    /** Package-private so TenantConfigValidator can apply the same code format to X-Tenant-Id. */
    // Hyphen and underscore are allowed because codes of that shape are already live (JOSEP-PARSHAD,
    // JOSEP_PARSHAD) and both are legal as a Keycloak realm name and as a Postgres schema name — the
    // tenant-migration library independently permits _ $ - . for exactly that reason. Excluding them
    // would also freeze those tenants: update carries the existing code through this same check, so a
    // code this rejects can never be updated again.
    static final Pattern TENANT_CODE_REGEX = Pattern.compile("^[A-Z0-9_-]+$");
    private static final Pattern PHONE_REGEX = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern ADDITIONAL_ATTR_KEY_REGEX = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");

    /** Byte length, matching Go's len(string). */
    private static int blen(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean blank(String s) {
        return s == null || s.strip().isEmpty();
    }

    public static class ListQueryParams {
        public String code;
        public String name;
        public String email;
        /** Null means "no filter" — both active and inactive tenants are returned. */
        public Boolean isActive;
        public int page = 1;
        public int size = 20;
    }

    /** Mirrors ValidateTenantListQuery — returns parsed params; errors via out-param list. */
    public static ListQueryParams validateTenantListQuery(String code, String name, String email,
                                                          String isActiveStr, String pageStr,
                                                          String sizeStr, List<String> errs) {
        ListQueryParams out = new ListQueryParams();
        out.code = code;
        out.name = name;
        out.email = email;
        out.isActive = parseFilterBool(isActiveStr, "isActive filter", errs);

        if (blen(code) > Constants.MAX_QUERY_FILTER_LENGTH) {
            errs.add(String.format("code filter must be at most %d characters (got %d)",
                    Constants.MAX_QUERY_FILTER_LENGTH, blen(code)));
        }
        if (blen(name) > Constants.MAX_QUERY_FILTER_LENGTH) {
            errs.add(String.format("name filter must be at most %d characters (got %d)",
                    Constants.MAX_QUERY_FILTER_LENGTH, blen(name)));
        }
        if (blen(email) > Constants.MAX_QUERY_FILTER_LENGTH) {
            errs.add(String.format("email filter must be at most %d characters (got %d)",
                    Constants.MAX_QUERY_FILTER_LENGTH, blen(email)));
        }
        if (pageStr != null && !pageStr.isEmpty()) {
            Integer page = parseInt(pageStr);
            if (page == null) {
                errs.add(String.format("page must be a positive integer (got %s)", quote(pageStr)));
            } else if (page < 1) {
                errs.add(String.format("page must be at least 1 (got %d)", page));
            } else {
                out.page = page;
            }
        }
        if (sizeStr != null && !sizeStr.isEmpty()) {
            Integer size = parseInt(sizeStr);
            if (size == null) {
                errs.add(String.format("size must be a positive integer (got %s)", quote(sizeStr)));
            } else if (size < 1) {
                errs.add(String.format("size must be at least 1 (got %d)", size));
            } else if (size > Constants.MAX_PAGE_SIZE) {
                errs.add(String.format("size must be at most %d (got %d)", Constants.MAX_PAGE_SIZE, size));
            } else {
                out.size = size;
            }
        }
        return out;
    }

    /** Mirrors ValidateUUIDPath. */
    public static List<String> validateUUIDPath(String fieldName, String id) {
        List<String> e = new ArrayList<>();
        if (id == null || id.strip().isEmpty()) {
            e.add(String.format("%s path parameter is required", fieldName));
            return e;
        }
        if (!isUUID(id)) {
            e.add(String.format("%s must be a valid UUID (got %s)", fieldName, quote(id)));
        }
        return e;
    }

    public static List<String> validateTenantID(String id) {
        return validateUUIDPath("tenant id", id);
    }

    /** Mirrors ValidateTenantCreateRequest. Returns the (possibly empty) error list. */
    public static List<String> validateTenantCreateRequest(TenantCreateRequest req) {
        List<String> errs = new ArrayList<>();
        if (req == null) {
            errs.add("request body is required");
            return errs;
        }
        errs.addAll(validatePassword(req.getPassword()));
        errs.addAll(validateTenantEntity(Mappers.tenantCreateRequestToEntity(req)));
        return errs;
    }

    private static List<String> validatePassword(String password) {
        List<String> e = new ArrayList<>();
        // Blank, not just empty, so this agrees with the service's generate-or-not decision: a
        // whitespace-only password is absent, not a short one, and must not be rejected for length.
        if (password == null || password.isBlank()) {
            return e; // absent triggers server-side generation — not an error
        }
        if (blen(password) < Constants.MIN_PASSWORD_LENGTH) {
            e.add(String.format("password must be at least %d characters (got %d)",
                    Constants.MIN_PASSWORD_LENGTH, blen(password)));
        } else if (blen(password) > Constants.MAX_PASSWORD_LENGTH) {
            e.add(String.format("password must be at most %d characters (got %d)",
                    Constants.MAX_PASSWORD_LENGTH, blen(password)));
        }
        return e;
    }

    /** Mirrors ValidateTenantUpdateRequest. */
    public static List<String> validateTenantUpdateRequest(TenantUpdateRequest req) {
        List<String> errs = new ArrayList<>();
        if (req == null) {
            errs.add("request body is required");
            return errs;
        }
        errs.addAll(validatePtrPhone(req.getPhone()));
        errs.addAll(validatePtrMinMax("address", req.getAddress(),
                Constants.MIN_ADDRESS_LENGTH, Constants.MAX_ADDRESS_LENGTH));
        errs.addAll(validatePtrMinMax("city", req.getCity(),
                Constants.MIN_CITY_LENGTH, Constants.MAX_CITY_LENGTH));
        errs.addAll(validatePtrMinMax("state", req.getState(),
                Constants.MIN_STATE_LENGTH, Constants.MAX_STATE_LENGTH));
        errs.addAll(validatePtrMinMax("pincode", req.getPincode(),
                Constants.MIN_PINCODE_LENGTH, Constants.MAX_PINCODE_LENGTH));
        errs.addAll(validatePtrMinMax("country", req.getCountry(),
                Constants.MIN_COUNTRY_LENGTH, Constants.MAX_COUNTRY_LENGTH));
        errs.addAll(validateAdditionalAttributesMap(req.getAdditionalAttributes()));
        return errs;
    }

    /** Mirrors ValidateTenantEntity. */
    public static List<String> validateTenantEntity(TenantEntity t) {
        List<String> errs = new ArrayList<>();
        errs.addAll(validateName(t.getName()));
        errs.addAll(validateEmail(t.getEmail()));
        errs.addAll(validateCode(t.getCode()));
        errs.addAll(validateId(t.getId()));
        errs.addAll(validatePtrPhone(t.getPhone()));
        errs.addAll(validatePtrMinMax("address", t.getAddress(),
                Constants.MIN_ADDRESS_LENGTH, Constants.MAX_ADDRESS_LENGTH));
        errs.addAll(validatePtrMinMax("city", t.getCity(),
                Constants.MIN_CITY_LENGTH, Constants.MAX_CITY_LENGTH));
        errs.addAll(validatePtrMinMax("state", t.getState(),
                Constants.MIN_STATE_LENGTH, Constants.MAX_STATE_LENGTH));
        errs.addAll(validatePtrMinMax("pincode", t.getPincode(),
                Constants.MIN_PINCODE_LENGTH, Constants.MAX_PINCODE_LENGTH));
        errs.addAll(validatePtrMinMax("country", t.getCountry(),
                Constants.MIN_COUNTRY_LENGTH, Constants.MAX_COUNTRY_LENGTH));
        errs.addAll(validateAdditionalAttributesMap(t.getAdditionalAttributes()));
        return errs;
    }

    private static List<String> validateName(String name) {
        List<String> e = new ArrayList<>();
        if (blank(name)) {
            e.add("name is required");
        } else if (blen(name) > Constants.MAX_NAME_LENGTH) {
            e.add(String.format("name must be at most %d characters (got %d)",
                    Constants.MAX_NAME_LENGTH, blen(name)));
        }
        return e;
    }

    private static List<String> validateEmail(String email) {
        List<String> e = new ArrayList<>();
        if (blank(email)) {
            e.add("email is required");
            return e;
        }
        if (blen(email) > Constants.MAX_EMAIL_LENGTH) {
            e.add(String.format("email must be at most %d characters (got %d)",
                    Constants.MAX_EMAIL_LENGTH, blen(email)));
            return e;
        }
        if (!EMAIL_REGEX.matcher(email).matches()) {
            e.add("email is not a valid address");
        }
        return e;
    }

    /**
     * Validates the code {@link com.digit.account.util.CodeGen#generateCodeFromName} derived from
     * a name. Enrichment produces the code after {@link #validateTenantCreateRequest} has already
     * run, so without this check the derived code reaches the database unvalidated. Unlike
     * {@link #validateCode} an empty result is an error here: a name with no alphanumeric
     * characters derives no code, and the tenant would have no usable identity.
     */
    public static List<String> validateDerivedCode(String code, String name) {
        List<String> e = new ArrayList<>();
        if (code == null || code.isEmpty()) {
            e.add(String.format("name %s derives an empty tenant code; the name must contain at "
                    + "least one letter or digit", quote(name)));
            return e;
        }
        e.addAll(validateCode(code));
        return e;
    }

    private static List<String> validateCode(String code) {
        List<String> e = new ArrayList<>();
        if (code == null || code.isEmpty()) {
            return e;
        }
        if (blen(code) > Constants.MAX_CODE_LENGTH) {
            e.add(String.format("code must be at most %d characters (got %d)",
                    Constants.MAX_CODE_LENGTH, blen(code)));
            return e;
        }
        if (!TENANT_CODE_REGEX.matcher(code).matches()) {
            e.add("code must contain only uppercase letters, digits, hyphen and underscore");
        }
        return e;
    }

    /**
     * Optional counterpart of {@link TenantConfigValidator#validateTenantCodeHeader}. An absent
     * header means "unscoped" rather than an error: this service is the tenant registry, so a
     * platform caller legitimately operates across every tenant. A supplied value still has to be
     * well formed, checked by the same rules as the config endpoints so both report alike.
     *
     * <p>X-Tenant-Id carries a tenant's code, because a tenant has no identifier separate from it.
     * That mapping is why the value is format-checked as a code while every name here says tenantId.
     */
    public static List<String> validateOptionalTenantIdHeader(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new ArrayList<>();
        }
        return TenantConfigValidator.validateTenantCodeAs("X-Tenant-Id", tenantId);
    }

    /**
     * Folds an optional tenantId scope into the {@code code} filter of a list query. No scope leaves
     * the query untouched; a scope alone narrows it to that tenant. Both present and disagreeing is
     * reported rather than resolved: silently letting one win would turn a caller's bug into an
     * empty page or, worse, into results outside the scope they asked for.
     */
    public static String resolveTenantIdScope(String tenantId, String code, List<String> errs) {
        if (tenantId == null || tenantId.isBlank()) {
            return code;
        }
        if (code == null || code.isEmpty()) {
            return tenantId;
        }
        if (!code.equals(tenantId)) {
            errs.add(String.format("code %s does not match the X-Tenant-Id scope %s",
                    quote(code), quote(tenantId)));
        }
        return tenantId;
    }

    private static List<String> validateId(String id) {
        List<String> e = new ArrayList<>();
        if (id == null || id.isEmpty()) {
            return e;
        }
        if (blen(id) > Constants.MAX_ID_LENGTH) {
            e.add(String.format("id must be at most %d characters (got %d)",
                    Constants.MAX_ID_LENGTH, blen(id)));
        }
        return e;
    }

    private static List<String> validatePtrPhone(String p) {
        List<String> e = new ArrayList<>();
        if (p == null || p.isEmpty()) {
            return e;
        }
        if (blen(p) > Constants.MAX_PHONE_LENGTH) {
            e.add(String.format("phone must be at most %d characters (got %d)",
                    Constants.MAX_PHONE_LENGTH, blen(p)));
            return e;
        }
        if (!PHONE_REGEX.matcher(p).matches()) {
            e.add("phone must be in E.164 format (e.g. +254202229000)");
        }
        return e;
    }

    private static List<String> validatePtrMinMax(String name, String p, int min, int max) {
        List<String> e = new ArrayList<>();
        if (p == null || p.isEmpty()) {
            return e;
        }
        if (blen(p) < min) {
            e.add(String.format("%s must be at least %d characters (got %d)", name, min, blen(p)));
        } else if (blen(p) > max) {
            e.add(String.format("%s must be at most %d characters (got %d)", name, max, blen(p)));
        }
        return e;
    }

    private static List<String> validateAdditionalAttributesMap(Map<String, Object> attrs) {
        List<String> errs = new ArrayList<>();
        if (attrs == null || attrs.isEmpty()) {
            return errs;
        }
        if (attrs.size() > Constants.MAX_ADDITIONAL_ATTRIBUTES_COUNT) {
            errs.add(String.format("additionalAttributes must contain at most %d entries (got %d)",
                    Constants.MAX_ADDITIONAL_ATTRIBUTES_COUNT, attrs.size()));
            return errs;
        }
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (blen(k) > Constants.MAX_ADDITIONAL_ATTRIBUTES_KEY_LENGTH) {
                errs.add(String.format("additionalAttributes key %s exceeds maximum length of %d",
                        quote(k), Constants.MAX_ADDITIONAL_ATTRIBUTES_KEY_LENGTH));
            }
            if (!ADDITIONAL_ATTR_KEY_REGEX.matcher(k).matches()) {
                errs.add(String.format("additionalAttributes key %s must match ^[a-zA-Z0-9_.-]+$", quote(k)));
            }
            if (!(v instanceof String s)) {
                errs.add(String.format("additionalAttributes value for key %s must be a string", quote(k)));
                continue;
            }
            if (blen(s) > Constants.MAX_ADDITIONAL_ATTRIBUTES_VALUE_LENGTH) {
                errs.add(String.format("additionalAttributes value for key %s exceeds maximum length of %d",
                        quote(k), Constants.MAX_ADDITIONAL_ATTRIBUTES_VALUE_LENGTH));
            }
        }
        return errs;
    }

    // ---- helpers ----

    static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses an optional tri-state filter: absent leaves the filter off, and only the literals
     * true/false are accepted. Deliberately not Boolean.parseBoolean, which maps every unrecognised
     * value to false — a typo like {@code ?isActive=ture} would then silently return only the
     * inactive rows instead of reporting the mistake.
     */
    static Boolean parseFilterBool(String s, String fieldName, List<String> errs) {
        if (s == null || s.strip().isEmpty()) {
            return null;
        }
        String v = s.strip();
        if ("true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        errs.add(String.format("%s must be true or false (got %s)", fieldName, quote(s)));
        return null;
    }

    static boolean isUUID(String s) {
        try {
            UUID.fromString(s);
            // java's UUID.fromString is lenient with short groups; enforce canonical 36-char form
            return s.length() == 36;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Renders a value like Go's %q (double-quoted). */
    static String quote(String s) {
        return "\"" + s + "\"";
    }
}
