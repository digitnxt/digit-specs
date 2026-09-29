package com.digit.individual.service;

import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Individual;
import com.digit.individual.model.RequestContext;
import com.digit.individual.observability.BusinessMetrics;
import com.digit.individual.pubsub.EventPublisher;
import com.digit.individual.repository.IndividualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Update and delete reuse the record the validator already loaded, so each request reads the
 * individual (and its children) no more often than the response needs.
 */
class IndividualServiceTest {

    private IndividualRepository repo;
    private IndividualService svc;
    private final RequestContext rc = new RequestContext("t1", "u1", "r1");

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(IndividualRepository.class);
        svc = new IndividualService(repo, Mockito.mock(EnrichmentService.class),
                Mockito.mock(EncryptionService.class), Mockito.mock(EventPublisher.class),
                new IndividualProperties(), Mockito.mock(BusinessMetrics.class));
    }

    private static Individual individual(String id, int version) {
        Individual ind = new Individual();
        ind.setId(id);
        ind.setTenantId("t1");
        ind.setRowVersion(version);
        return ind;
    }

    @Test
    void update_reusesValidatedRecord_readsOnlyForTheResponse() {
        Individual existing = individual("i1", 3);
        Individual refreshed = individual("i1", 4);
        Mockito.when(repo.update(any(), Mockito.eq(3))).thenReturn(true);
        Mockito.when(repo.findById("i1", "t1")).thenReturn(refreshed);

        Individual result = svc.updateIndividual(individual("i1", 3), existing, rc);

        assertSame(refreshed, result);
        // One read: the post-update refresh. The pre-update record came from the validator.
        Mockito.verify(repo, Mockito.times(1)).findById("i1", "t1");
    }

    @Test
    void delete_reusesValidatedRecord_withoutReloading() {
        Individual existing = individual("i1", 2);

        Individual result = svc.deleteIndividual(existing, rc);

        assertSame(existing, result);
        Mockito.verify(repo).delete(Mockito.eq("i1"), Mockito.eq("t1"), anyLong());
        Mockito.verify(repo, Mockito.never()).findById(any(), any());
    }

    @Test
    void get_loadsById_withoutSearchOrCount() {
        Individual stored = individual("i1", 1);
        Mockito.when(repo.findById("i1", "t1")).thenReturn(stored);

        assertSame(stored, svc.getIndividual("i1", "t1"));
        assertNull(svc.getIndividual("missing", "t1"));
        Mockito.verify(repo, Mockito.never()).search(any(), any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean());
    }
}
