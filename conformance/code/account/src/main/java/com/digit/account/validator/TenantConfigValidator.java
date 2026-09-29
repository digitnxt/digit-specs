package com.digit.account.validator;

import com.digit.account.constants.Constants;
import com.digit.account.model.TenantConfigEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Tenant config validation. Mirrors Go internal/validator/tenant_config_validator.go. */
public final class TenantConfigValidator {
    private TenantConfigValidator() {}

    private static int blen(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean blank(String s) {
        return s == null || s.strip().isEmpty();
    }

    public static class ListQueryParams {
        public String tenantId;
        public String configKey;
        /** Null means "no filter" — both active and inactive configs are returned. */
        public Boolean isActive;
        public int page = 1;
        public int size = 20;
    }

    /**
     * Checks a tenant-code-valued field under the caller's own field name, so the same rules back
     * both the body-derived entity and the X-Tenant-Id header without duplicating the format.
     * Mirrors Go validateTenantCodeAs.
     */
    static List<String> validateTenantCodeAs(String fieldName, String code) {
        List<String> e = new ArrayList<>();
        if (blen(code) > Constants.MAX_CODE_LENGTH) {
            e.add(String.format("%s must be at most %d characters (got %d)",
                    fieldName, Constants.MAX_CODE_LENGTH, blen(code)));
            return e;
        }
        if (!TenantValidator.TENANT_CODE_REGEX.matcher(code).matches()) {
            e.add(String.format("%s must be a tenant code containing only uppercase letters, "
                    + "digits, hyphen and underscore (got %s)", fieldName, TenantValidator.quote(code)));
        }
        return e;
    }

    /**
     * Enforces that X-Tenant-Id carries a well-formed tenant code. Shared by every tenant-scoped
     * config endpoint. Mirrors Go ValidateTenantCodeHeader.
     */
    public static List<String> validateTenantCodeHeader(String tenantCode) {
        if (blank(tenantCode)) {
            return new ArrayList<>(List.of("X-Tenant-Id header is required to scope the request"));
        }
        return validateTenantCodeAs("X-Tenant-Id", tenantCode);
    }

    /**
     * Mirrors ValidateTenantConfigEntity. {@code tenantId} carries the tenant code, so it is
     * checked against the tenant-code format rather than the UUID format.
     */
    public static List<String> validateTenantConfigEntity(TenantConfigEntity c) {
        List<String> errs = new ArrayList<>();

        if (blank(c.getTenantId())) {
            errs.add("tenantId is required");
        } else {
            errs.addAll(validateTenantCodeAs("tenantId", c.getTenantId()));
        }

        if (blank(c.getConfigKey())) {
            errs.add("configKey is required");
        } else if (blen(c.getConfigKey()) > Constants.MAX_CONFIG_KEY_LENGTH) {
            errs.add(String.format("configKey must be at most %d characters (got %d)",
                    Constants.MAX_CONFIG_KEY_LENGTH, blen(c.getConfigKey())));
        }

        if (blank(c.getConfigValue())) {
            errs.add("configValue is required");
        } else if (blen(c.getConfigValue()) > Constants.MAX_CONFIG_VALUE_LENGTH) {
            errs.add(String.format("configValue must be at most %d characters (got %d)",
                    Constants.MAX_CONFIG_VALUE_LENGTH, blen(c.getConfigValue())));
        }

        if (c.getDescription() != null && !c.getDescription().isEmpty()) {
            if (blen(c.getDescription()) < Constants.MIN_CONFIG_DESCRIPTION_LENGTH) {
                errs.add(String.format("description must be at least %d characters (got %d)",
                        Constants.MIN_CONFIG_DESCRIPTION_LENGTH, blen(c.getDescription())));
            }
            if (blen(c.getDescription()) > Constants.MAX_CONFIG_DESCRIPTION_LENGTH) {
                errs.add(String.format("description must be at most %d characters (got %d)",
                        Constants.MAX_CONFIG_DESCRIPTION_LENGTH, blen(c.getDescription())));
            }
        }

        if (c.getId() != null && !c.getId().isEmpty()
                && blen(c.getId()) > Constants.MAX_ID_LENGTH) {
            errs.add(String.format("id must be at most %d characters (got %d)",
                    Constants.MAX_ID_LENGTH, blen(c.getId())));
        }
        return errs;
    }

    /** Mirrors ValidateTenantConfigListQuery. {@code tenantCode} comes from X-Tenant-Id. */
    public static ListQueryParams validateTenantConfigListQuery(String tenantCode, String configKey,
                                                                String isActiveStr, String pageStr,
                                                                String sizeStr, List<String> errs) {
        ListQueryParams out = new ListQueryParams();
        out.tenantId = tenantCode;
        out.configKey = configKey;
        out.isActive = TenantValidator.parseFilterBool(isActiveStr, "isActive filter", errs);

        errs.addAll(validateTenantCodeHeader(tenantCode));

        if (blen(configKey) > Constants.MAX_CONFIG_KEY_LENGTH) {
            errs.add(String.format("configKey filter must be at most %d characters (got %d)",
                    Constants.MAX_CONFIG_KEY_LENGTH, blen(configKey)));
        }

        if (pageStr != null && !pageStr.isEmpty()) {
            Integer page = TenantValidator.parseInt(pageStr);
            if (page == null) {
                errs.add(String.format("page must be a positive integer (got %s)",
                        TenantValidator.quote(pageStr)));
            } else if (page < 1) {
                errs.add(String.format("page must be at least 1 (got %d)", page));
            } else {
                out.page = page;
            }
        }
        if (sizeStr != null && !sizeStr.isEmpty()) {
            Integer size = TenantValidator.parseInt(sizeStr);
            if (size == null) {
                errs.add(String.format("size must be a positive integer (got %s)",
                        TenantValidator.quote(sizeStr)));
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
}
