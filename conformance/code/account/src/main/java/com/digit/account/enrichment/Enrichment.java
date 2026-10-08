package com.digit.account.enrichment;

import com.digit.account.model.TenantConfigEntity;
import com.digit.account.model.TenantEntity;
import com.digit.account.util.CodeGen;

import java.util.HashMap;
import java.util.UUID;

/** Server-managed field enrichment. Mirrors Go internal/enrichment/*.go. */
public final class Enrichment {
    private Enrichment() {}

    /** Mirrors EnrichTenantEntity. */
    public static void enrichTenantEntity(TenantEntity t, String clientId, String requestId) {
        // Default the audit actor to "admin" when no caller identity (X-Client-Id) was supplied.
        if (clientId == null || clientId.isEmpty()) {
            clientId = "admin";
        }
        long now = System.currentTimeMillis();
        if (t.getId() == null || t.getId().isEmpty()) {
            t.setId(UUID.randomUUID().toString());
        }
        if ((t.getCode() == null || t.getCode().isEmpty())
                && t.getName() != null && !t.getName().isEmpty()) {
            t.setCode(CodeGen.generateCodeFromName(t.getName()));
        }
        if (t.getAdditionalAttributes() == null) {
            t.setAdditionalAttributes(new HashMap<>());
        }
        t.setVersion(1);
        t.setCreatedBy(clientId);
        t.setModifiedBy(clientId);
        t.setCreatedTime(now);
        t.setModifiedTime(now);
        t.setRequestId(requestId);
    }

    /** Mirrors EnrichTenantConfigEntity. */
    public static void enrichTenantConfigEntity(TenantConfigEntity c, String clientId, String requestId) {
        // Default the audit actor to "admin" when no caller identity (X-Client-Id) was supplied.
        if (clientId == null || clientId.isEmpty()) {
            clientId = "admin";
        }
        long now = System.currentTimeMillis();
        if (c.getId() == null || c.getId().isEmpty()) {
            c.setId(UUID.randomUUID().toString());
        }
        c.setActive(true);
        c.setVersion(1);
        c.setCreatedBy(clientId);
        c.setModifiedBy(clientId);
        c.setCreatedTime(now);
        c.setModifiedTime(now);
        c.setRequestId(requestId);
    }
}
