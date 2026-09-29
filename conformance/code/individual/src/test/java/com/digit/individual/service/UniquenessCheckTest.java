package com.digit.individual.service;

import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Config;
import com.digit.individual.model.Individual;
import com.digit.individual.repository.ConfigRepository;
import com.digit.individual.repository.IndividualRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The mobile uniqueness check is one keyed-hash lookup; there is no plaintext-column fallback. */
class UniquenessCheckTest {

    private static RequestValidator validator(IndividualRepository repo, String criteria) {
        Config cfg = new Config();
        cfg.setUniquenessCriteria(criteria);
        ConfigRepository cfgRepo = Mockito.mock(ConfigRepository.class);
        Mockito.when(cfgRepo.getByTenant("t1")).thenReturn(cfg);
        IndividualProperties props = new IndividualProperties();
        props.setHmacSecret("pepper");
        return new RequestValidator(repo, cfgRepo, JsonMapper.builder().build(), props);
    }

    private static Individual newIndividual() {
        Individual ind = new Individual();
        ind.setTenantId("t1");
        ind.setGivenName("Ravi");
        ind.setMobileNumber("9800000001");
        return ind;
    }

    @Test
    void newMobile_isOneHashLookup_andNothingElse() {
        IndividualRepository repo = Mockito.mock(IndividualRepository.class);

        assertDoesNotThrow(() -> validator(repo, "[\"mobilenumber\"]").validateCreate(newIndividual()));

        String hash = HashUtil.hashMobileNumber("pepper".getBytes(), "9800000001");
        Mockito.verify(repo).findByMobileHash(hash, "t1");
        Mockito.verifyNoMoreInteractions(repo);
    }

    @Test
    void existingMobile_isRejectedAs409() {
        IndividualRepository repo = Mockito.mock(IndividualRepository.class);
        Mockito.when(repo.findByMobileHash(Mockito.any(), Mockito.eq("t1"))).thenReturn(new Individual());

        CustomException ex = assertThrows(CustomException.class,
                () -> validator(repo, "[\"mobilenumber\"]").validateCreate(newIndividual()));
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
    }
}
