package com.digit.account.model;

import java.util.ArrayList;
import java.util.List;

/** Entity ⇄ DTO mappers. Mirrors Go models/tenant_dto.go + tenant_config_dto.go mapping functions. */
public final class Mappers {
    private Mappers() {}

    // ---------- Tenant ----------

    public static TenantResponse tenantFromEntity(TenantEntity e) {
        if (e == null) {
            return null;
        }
        TenantResponse r = new TenantResponse();
        r.setId(e.getId());
        r.setCode(e.getCode());
        r.setName(e.getName());
        r.setEmail(e.getEmail());
        r.setPhone(e.getPhone());
        r.setAddress(e.getAddress());
        r.setCity(e.getCity());
        r.setState(e.getState());
        r.setPincode(e.getPincode());
        r.setCountry(e.getCountry());
        r.setFirstLoginUrls(e.getFirstLoginUrls());
        r.setActive(e.isActive());
        r.setPasswordGenerated(e.isPasswordGenerated());
        r.setAdditionalAttributes(e.getAdditionalAttributes());
        r.setVersion(e.getVersion());
        r.setAuditDetail(AuditDetail.newAuditDetail(e.getCreatedBy(), e.getModifiedBy(),
                e.getCreatedTime(), e.getModifiedTime()));
        return r;
    }

    public static List<TenantResponse> tenantsFromEntities(List<TenantEntity> es) {
        List<TenantResponse> out = new ArrayList<>(es.size());
        for (TenantEntity e : es) {
            TenantResponse r = tenantFromEntity(e);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public static TenantEntity tenantCreateRequestToEntity(TenantCreateRequest d) {
        if (d == null) {
            return null;
        }
        TenantEntity e = new TenantEntity();
        e.setName(d.getName());
        e.setEmail(d.getEmail());
        e.setPhone(d.getPhone());
        e.setAddress(d.getAddress());
        e.setCity(d.getCity());
        e.setState(d.getState());
        e.setPincode(d.getPincode());
        e.setCountry(d.getCountry());
        e.setAdditionalAttributes(d.getAdditionalAttributes());
        return e;
    }

    /**
     * Builds the entity written by a PUT. Identity + server-managed fields are preserved from
     * {@code existing}; mutable fields follow "omit to retain". Version is incremented by one.
     */
    public static TenantEntity tenantUpdateRequestToEntity(TenantEntity existing, TenantUpdateRequest d,
                                                           String clientId, String requestId,
                                                           long modifiedTime) {
        if (existing == null || d == null) {
            return null;
        }
        // Default the audit actor to "admin" when no caller identity (X-Client-Id) was supplied.
        if (clientId == null || clientId.isEmpty()) {
            clientId = "admin";
        }
        String rid = existing.getRequestId();
        if (requestId != null && !requestId.isEmpty()) {
            rid = requestId;
        }
        TenantEntity out = new TenantEntity();
        out.setId(existing.getId());
        out.setCode(existing.getCode());
        out.setName(existing.getName());
        out.setEmail(existing.getEmail());
        out.setActive(existing.isActive());
        out.setPasswordGenerated(existing.isPasswordGenerated());
        out.setCreatedBy(existing.getCreatedBy());
        out.setCreatedTime(existing.getCreatedTime());

        out.setPhone(existing.getPhone());
        out.setAddress(existing.getAddress());
        out.setCity(existing.getCity());
        out.setState(existing.getState());
        out.setPincode(existing.getPincode());
        out.setCountry(existing.getCountry());
        // Server-set at creation and never patchable, like code and the audit fields.
        out.setFirstLoginUrls(existing.getFirstLoginUrls());
        out.setAdditionalAttributes(existing.getAdditionalAttributes());

        out.setVersion(existing.getVersion() + 1);
        out.setModifiedBy(clientId);
        out.setModifiedTime(modifiedTime);
        out.setRequestId(rid);

        if (d.getPhone() != null) {
            out.setPhone(d.getPhone());
        }
        if (d.getAddress() != null) {
            out.setAddress(d.getAddress());
        }
        if (d.getCity() != null) {
            out.setCity(d.getCity());
        }
        if (d.getState() != null) {
            out.setState(d.getState());
        }
        if (d.getPincode() != null) {
            out.setPincode(d.getPincode());
        }
        if (d.getCountry() != null) {
            out.setCountry(d.getCountry());
        }
        if (d.getIsActive() != null) {
            out.setActive(d.getIsActive());
        }
        if (d.getAdditionalAttributes() != null) {
            out.setAdditionalAttributes(d.getAdditionalAttributes());
        }
        return out;
    }

    // ---------- TenantConfig ----------

    public static TenantConfigResponse tenantConfigFromEntity(TenantConfigEntity e) {
        if (e == null) {
            return null;
        }
        TenantConfigResponse r = new TenantConfigResponse();
        r.setId(e.getId());
        r.setTenantId(e.getTenantId());
        r.setConfigKey(e.getConfigKey());
        r.setConfigValue(e.getConfigValue());
        r.setDescription(e.getDescription());
        r.setActive(e.isActive());
        r.setVersion(e.getVersion());
        r.setAuditDetail(AuditDetail.newAuditDetail(e.getCreatedBy(), e.getModifiedBy(),
                e.getCreatedTime(), e.getModifiedTime()));
        return r;
    }

    public static List<TenantConfigResponse> tenantConfigsFromEntities(List<TenantConfigEntity> es) {
        List<TenantConfigResponse> out = new ArrayList<>(es.size());
        for (TenantConfigEntity e : es) {
            TenantConfigResponse r = tenantConfigFromEntity(e);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** {@code tenantCode} comes from the X-Tenant-Id header, not the body. */
    public static TenantConfigEntity tenantConfigCreateRequestToEntity(TenantConfigCreateRequest d,
                                                                       String tenantCode) {
        if (d == null) {
            return null;
        }
        TenantConfigEntity e = new TenantConfigEntity();
        e.setTenantId(tenantCode);
        e.setConfigKey(d.getConfigKey());
        e.setConfigValue(d.getConfigValue());
        e.setDescription(d.getDescription() == null ? "" : d.getDescription());
        return e;
    }

    public static TenantConfigEntity tenantConfigUpdateRequestToEntity(TenantConfigEntity existing,
                                                                       TenantConfigUpdateRequest d,
                                                                       String clientId, String requestId,
                                                                       long modifiedTime) {
        if (existing == null || d == null) {
            return null;
        }
        // Default the audit actor to "admin" when no caller identity (X-Client-Id) was supplied.
        if (clientId == null || clientId.isEmpty()) {
            clientId = "admin";
        }
        String desc = existing.getDescription();
        if (d.isDescriptionPresent()) {
            desc = d.getDescription();
        }
        String rid = existing.getRequestId();
        if (requestId != null && !requestId.isEmpty()) {
            rid = requestId;
        }
        TenantConfigEntity out = new TenantConfigEntity();
        out.setId(existing.getId());
        out.setTenantId(existing.getTenantId());
        out.setActive(existing.isActive());
        out.setCreatedBy(existing.getCreatedBy());
        out.setCreatedTime(existing.getCreatedTime());

        // Seed every mutable field from the existing row first, so an omitted field retains rather
        // than nulling a NOT NULL column. Both keys stay non-null through the merge as a result,
        // which is what keeps the caller's duplicate-key comparison safe to dereference.
        out.setConfigKey(existing.getConfigKey());
        out.setConfigValue(existing.getConfigValue());
        out.setDescription(desc);

        if (d.getConfigKey() != null) {
            out.setConfigKey(d.getConfigKey());
        }
        if (d.getConfigValue() != null) {
            out.setConfigValue(d.getConfigValue());
        }
        if (d.getIsActive() != null) {
            out.setActive(d.getIsActive());
        }

        out.setVersion(existing.getVersion() + 1);
        out.setModifiedBy(clientId);
        out.setModifiedTime(modifiedTime);
        out.setRequestId(rid);
        return out;
    }

    /** signup-initiate → create request (identical shape). Mirrors Go signupToCreateRequest. */
    public static TenantCreateRequest signupToCreateRequest(SignupInitiateRequest s) {
        TenantCreateRequest c = new TenantCreateRequest();
        c.setName(s.getName());
        c.setEmail(s.getEmail());
        c.setPassword(s.getPassword());
        c.setPhone(s.getPhone());
        c.setAddress(s.getAddress());
        c.setCity(s.getCity());
        c.setState(s.getState());
        c.setPincode(s.getPincode());
        c.setCountry(s.getCountry());
        c.setAdditionalAttributes(s.getAdditionalAttributes());
        return c;
    }
}
