package com.digit.individual.service;

import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Address;
import com.digit.individual.model.Document;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import com.digit.individual.model.RequestContext;
import com.digit.individual.model.SearchCriteria;
import com.digit.individual.observability.BusinessMetrics;
import com.digit.individual.pubsub.EventPublisher;
import com.digit.individual.repository.IndividualRepository;
import com.digit.individual.constants.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core individual business logic. Validation is owned by the controller layer; these methods assume
 * input already passed the validator.
 */
@Service
public class IndividualService {

    private final IndividualRepository repo;
    private final EnrichmentService enrichmentService;
    private final EncryptionService encryptionService;
    private final EventPublisher eventPublisher;
    private final IndividualProperties props;
    private final BusinessMetrics businessMetrics;

    public IndividualService(IndividualRepository repo, EnrichmentService enrichmentService,
                             EncryptionService encryptionService, EventPublisher eventPublisher,
                             IndividualProperties props, BusinessMetrics businessMetrics) {
        this.repo = repo;
        this.enrichmentService = enrichmentService;
        this.encryptionService = encryptionService;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.businessMetrics = businessMetrics;
    }

    public Individual createIndividual(Individual ind, RequestContext reqContext) {
        enrichmentService.enrichForCreate(ind, reqContext);
        Runnable restorePlaintext = encryptionService.encryptIndividual(ind);
        // A unique-constraint violation (concurrent create racing the app-level check) is translated
        // to a 409 in the repository layer; other DB failures propagate to tracer's 500 handler.
        repo.create(ind);
        businessMetrics.recordIndividualCreated(reqContext.getTenantId(), 1);
        eventPublisher.publishEvent(props.getPubsub().getTopics().getCreateIndividual(),
                props.getPubsub().getTopics().getCreateIndividual(),
                reqContext.getTenantId(), reqContext.getUserId(), ind, 1);
        // The response carries the plaintext the client sent; the event above carried ciphertext.
        restorePlaintext.run();
        return ind;
    }

    /**
     * Applies a validated PUT. {@code existing} is the stored record the validator loaded and
     * version-checked; the authoritative guard is the version-checked update (CAS) in the repository,
     * which also closes the race in the read->write window.
     */
    public Individual updateIndividual(Individual ind, Individual existing, RequestContext reqContext) {
        // Reconcile children: resolve id-less identifiers by type (B14) and reject any child id that
        // isn't an existing active child of this individual (B15).
        reconcileChildren(ind, existing);

        // Preserve server-managed fields. PUT: all else comes from the body.
        if (ind.getIndividualId() == null || ind.getIndividualId().isEmpty()) {
            ind.setIndividualId(existing.getIndividualId());
        }
        if (ind.getTenantId() == null || ind.getTenantId().isEmpty()) {
            ind.setTenantId(existing.getTenantId());
        }
        ind.setActive(existing.isActive());
        if (ind.getCreatedBy() == null || ind.getCreatedBy().isEmpty()) {
            ind.setCreatedBy(existing.getCreatedBy());
        }
        if (ind.getCreatedTime() == 0) {
            ind.setCreatedTime(existing.getCreatedTime());
        }
        // additionalDetails: full-replace (PUT) — whatever the client sent (including absent, i.e.
        // null) becomes the new value; existing is not merged in.

        // Capture the client-supplied version before enrichment bumps it; the repo uses it as the
        // optimistic guard (compare-and-swap) on the update.
        int expectedVersion = ind.getRowVersion();

        enrichmentService.enrichForUpdate(ind, reqContext);
        encryptionService.encryptIndividual(ind);

        // false => the row changed since we read it (lost the race) => 409. A unique-constraint
        // violation on the write is likewise a 409, translated in the repository layer; other DB
        // failures propagate to tracer's 500 handler.
        boolean updated = repo.update(ind, expectedVersion);
        if (!updated) {
            throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "Row version mismatch", HttpStatus.CONFLICT);
        }

        // Re-fetch so the response carries the full record (children may have been omitted in body).
        Individual refreshed = repo.findById(ind.getId(), reqContext.getTenantId());
        Individual result = refreshed != null ? refreshed : ind;

        businessMetrics.recordIndividualUpdated(reqContext.getTenantId(), 1);
        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpdateIndividual(),
                props.getPubsub().getTopics().getUpdateIndividual(),
                reqContext.getTenantId(), reqContext.getUserId(), result, result.getRowVersion());
        encryptionService.decryptIndividual(result);
        return result;
    }

    /** Soft-deletes {@code existing}, the stored record the validator loaded. */
    public Individual deleteIndividual(Individual existing, RequestContext reqContext) {
        enrichmentService.enrichForDelete(existing, reqContext);
        // DB failures are genuine infra errors; let them propagate to the tracer 500 handler.
        repo.delete(existing.getId(), reqContext.getTenantId(), System.currentTimeMillis());
        businessMetrics.recordIndividualDeleted(reqContext.getTenantId(), 1);
        eventPublisher.publishEvent(props.getPubsub().getTopics().getDeleteIndividual(),
                props.getPubsub().getTopics().getDeleteIndividual(),
                reqContext.getTenantId(), reqContext.getUserId(), existing, 1);
        return existing;
    }

    public IndividualRepository.SearchResult searchIndividuals(SearchCriteria criteria, int page, int size,
                                                               boolean includeDeleted, String tenantId) {
        // Hash plaintext mobile numbers before querying.
        if (criteria != null && criteria.getMobileNumber() != null && !criteria.getMobileNumber().isEmpty()) {
            List<String> hashed = new ArrayList<>();
            for (String m : criteria.getMobileNumber()) {
                if (m == null || m.isEmpty()) {
                    continue;
                }
                hashed.add(encryptionService.hashMobileNumber(m));
            }
            criteria.setMobileNumber(hashed);
        }

        int p = page < 1 ? ErrorCodes.DEFAULT_PAGE : page;
        int s = size <= 0 ? ErrorCodes.DEFAULT_PAGE_SIZE : size;
        if (s > ErrorCodes.MAX_PAGE_SIZE) {
            s = ErrorCodes.MAX_PAGE_SIZE;
        }

        // DB failures are genuine infra errors; let them propagate to the tracer 500 handler.
        IndividualRepository.SearchResult result = repo.search(criteria, tenantId, p, s, includeDeleted);
        encryptionService.decryptIndividuals(result.individuals());
        businessMetrics.recordIndividualSearched(tenantId, result.individuals().size());
        return result;
    }

    /** Loads one active individual by id, decrypted; null when absent. Counted as a search of one. */
    public Individual getIndividual(String id, String tenantId) {
        // DB failures are genuine infra errors; let them propagate to the tracer 500 handler.
        Individual ind = repo.findById(id, tenantId);
        if (ind != null) {
            encryptionService.decryptIndividual(ind);
        }
        businessMetrics.recordIndividualSearched(tenantId, ind == null ? 0 : 1);
        return ind;
    }

    public boolean individualExists(SearchCriteria criteria, String tenantId, boolean includeDeleted) {
        if (criteria != null && criteria.getMobileNumber() != null && !criteria.getMobileNumber().isEmpty()) {
            List<String> hashed = new ArrayList<>();
            for (String m : criteria.getMobileNumber()) {
                if (m == null || m.isEmpty()) {
                    continue;
                }
                hashed.add(encryptionService.hashMobileNumber(m));
            }
            criteria.setMobileNumber(hashed);
        }
        // DB failures are genuine infra errors; let them propagate to the tracer 500 handler.
        return repo.exists(criteria, tenantId, includeDeleted);
    }

    /**
     * Aligns a PUT request's children with the individual's existing active children:
     *   - B14: an id-less identifier whose identifierType matches an existing active one adopts that
     *     id, so it updates in place instead of inserting a duplicate that hits the
     *     (individualId, identifierType) unique index.
     *   - B15: any child carrying an id that is NOT an existing active child of this individual is
     *     rejected — a PUT must never reassign or modify another individual's child. (documents and
     *     addresses have no natural key, so an id-less one is always a new row.)
     */
    private void reconcileChildren(Individual ind, Individual existing) {
        Map<String, String> identByType = new HashMap<>();
        Set<String> identIds = new HashSet<>();
        for (Identifier i : existing.getIdentifiers()) {
            identByType.put(i.getIdentifierType(), i.getId());
            identIds.add(i.getId());
        }
        Set<String> addrIds = new HashSet<>();
        for (Address a : existing.getAddresses()) {
            addrIds.add(a.getId());
        }
        Set<String> docIds = new HashSet<>();
        for (Document d : existing.getDocuments()) {
            docIds.add(d.getId());
        }

        // B14: resolve id-less identifiers to the existing active one of the same type.
        for (Identifier i : ind.getIdentifiers()) {
            if ((i.getId() == null || i.getId().isEmpty()) && identByType.containsKey(i.getIdentifierType())) {
                i.setId(identByType.get(i.getIdentifierType()));
            }
        }

        // B15: a supplied child id must belong to this individual.
        for (Identifier i : ind.getIdentifiers()) {
            if (i.getId() != null && !i.getId().isEmpty() && !identIds.contains(i.getId())) {
                throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                        "identifier id does not belong to this individual: " + i.getId());
            }
        }
        for (Address a : ind.getAddresses()) {
            if (a.getId() != null && !a.getId().isEmpty() && !addrIds.contains(a.getId())) {
                throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                        "address id does not belong to this individual: " + a.getId());
            }
        }
        for (Document d : ind.getDocuments()) {
            if (d.getId() != null && !d.getId().isEmpty() && !docIds.contains(d.getId())) {
                throw new CustomException(ErrorCodes.VALIDATION_ERROR,
                        "document id does not belong to this individual: " + d.getId());
            }
        }
    }

}
