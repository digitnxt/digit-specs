package com.digit.individual.service;

import com.digit.individual.client.IdgenClient;
import com.digit.individual.config.IndividualProperties;
import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.model.Address;
import com.digit.individual.model.Document;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import com.digit.individual.model.RequestContext;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side enrichment for create/update/delete. Sets ids, audit fields, version, individualId
 * (via IDGen), and propagates them to nested entities.
 */
@Service
public class EnrichmentService {

    private final IdgenClient idgenClient;
    private final IndividualProperties.Idgen config;

    public EnrichmentService(IdgenClient idgenClient, IndividualProperties props) {
        this.idgenClient = idgenClient;
        this.config = props.getIdgen();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    public void enrichForCreate(Individual ind, RequestContext reqContext) {
        long now = now();

        // id is server-managed.
        ind.setId(uuid());

        // individualId via IDGen (readOnly per spec).
        Map<String, String> customVars = new HashMap<>();
        customVars.put("ORG", ind.getTenantId());
        List<String> ids;
        try {
            ids = idgenClient.generateIds(ind.getTenantId(), config.getFormat(), 1, customVars);
        } catch (RuntimeException e) {
            // idgen is a downstream dependency — surface as DOWNSTREAM_ERROR (502) with the specific
            // cause, not the tracer's generic 500 catch-all. Mirrors Go enrichment_service.
            throw new CustomException(ErrorCodes.DOWNSTREAM,
                    "failed to generate individualId: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        }
        if (!ids.isEmpty()) {
            ind.setIndividualId(ids.get(0));
        } else {
            ind.setIndividualId("IND-" + uuid().substring(0, 8));
        }

        if (reqContext != null) {
            ind.setCreatedBy(reqContext.getUserId());
            ind.setModifiedBy(reqContext.getUserId());
            ind.setRequestId(reqContext.getRequestId());
        }
        ind.setCreatedTime(now);
        ind.setModifiedTime(now);
        ind.setRowVersion(1);
        ind.setActive(true);

        for (Address a : ind.getAddresses()) {
            if (a.getId() == null || a.getId().isEmpty()) {
                a.setId(uuid());
            }
            a.setTenantId(ind.getTenantId());
            if (reqContext != null) {
                a.setCreatedBy(reqContext.getUserId());
                a.setModifiedBy(reqContext.getUserId());
                a.setRequestId(reqContext.getRequestId());
            }
            a.setCreatedTime(now);
            a.setModifiedTime(now);
            a.setActive(true); // present in the request => active on create
        }

        for (Identifier i : ind.getIdentifiers()) {
            if (i.getId() == null || i.getId().isEmpty()) {
                i.setId(uuid());
            }
            if (reqContext != null) {
                i.setCreatedBy(reqContext.getUserId());
                i.setModifiedBy(reqContext.getUserId());
                i.setRequestId(reqContext.getRequestId());
            }
            i.setCreatedTime(now);
            i.setModifiedTime(now);
            i.setActive(true); // present in the request => active on create
        }

        for (Document d : ind.getDocuments()) {
            if (d.getId() == null || d.getId().isEmpty()) {
                d.setId(uuid());
            }
            d.setIndividualId(ind.getId());
            if (reqContext != null) {
                d.setCreatedBy(reqContext.getUserId());
                d.setModifiedBy(reqContext.getUserId());
                d.setRequestId(reqContext.getRequestId());
            }
            d.setCreatedTime(now);
            d.setModifiedTime(now);
            d.setActive(true); // present in the request => active on create
        }
    }

    public void enrichForUpdate(Individual ind, RequestContext reqContext) {
        long now = now();

        if (reqContext != null) {
            ind.setModifiedBy(reqContext.getUserId());
            ind.setRequestId(reqContext.getRequestId());
        }
        ind.setModifiedTime(now);
        ind.setRowVersion(ind.getRowVersion() + 1);

        for (Address a : ind.getAddresses()) {
            a.setActive(true); // present in the request => active under PUT full-replace
            if (a.getId() == null || a.getId().isEmpty()) {
                a.setId(uuid());
                a.setTenantId(ind.getTenantId());
                if (reqContext != null) {
                    a.setCreatedBy(reqContext.getUserId());
                    a.setModifiedBy(reqContext.getUserId());
                    a.setRequestId(reqContext.getRequestId());
                }
                a.setCreatedTime(now);
                a.setModifiedTime(now);
            } else {
                if (reqContext != null) {
                    a.setModifiedBy(reqContext.getUserId());
                    a.setRequestId(reqContext.getRequestId());
                }
                a.setModifiedTime(now);
            }
        }

        for (Identifier i : ind.getIdentifiers()) {
            i.setActive(true); // present in the request => active under PUT full-replace
            if (i.getId() == null || i.getId().isEmpty()) {
                i.setId(uuid());
                if (reqContext != null) {
                    i.setCreatedBy(reqContext.getUserId());
                    i.setModifiedBy(reqContext.getUserId());
                    i.setRequestId(reqContext.getRequestId());
                }
                i.setCreatedTime(now);
                i.setModifiedTime(now);
            } else {
                if (reqContext != null) {
                    i.setModifiedBy(reqContext.getUserId());
                    i.setRequestId(reqContext.getRequestId());
                }
                i.setModifiedTime(now);
            }
        }

        for (Document d : ind.getDocuments()) {
            d.setActive(true); // present in the request => active under PUT full-replace
            d.setIndividualId(ind.getId());
            if (d.getId() == null || d.getId().isEmpty()) {
                d.setId(uuid());
                if (reqContext != null) {
                    d.setCreatedBy(reqContext.getUserId());
                    d.setModifiedBy(reqContext.getUserId());
                    d.setRequestId(reqContext.getRequestId());
                }
                d.setCreatedTime(now);
                d.setModifiedTime(now);
            } else {
                if (reqContext != null) {
                    d.setModifiedBy(reqContext.getUserId());
                    d.setRequestId(reqContext.getRequestId());
                }
                d.setModifiedTime(now);
            }
        }
    }

    public void enrichForDelete(Individual ind, RequestContext reqContext) {
        long now = now();
        ind.setActive(false);
        if (reqContext != null) {
            ind.setModifiedBy(reqContext.getUserId());
        }
        ind.setModifiedTime(now);
        for (Identifier i : ind.getIdentifiers()) {
            i.setActive(false);
            i.setModifiedTime(now);
            if (reqContext != null) {
                i.setModifiedBy(reqContext.getUserId());
            }
        }
    }
}
